ARG PYTHON_BASE_IMAGE=python:3.14-slim-bookworm
FROM ${PYTHON_BASE_IMAGE}

ARG PIP_INDEX_URL
ARG PIP_TRUSTED_HOST

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN addgroup --system --gid 10001 meguri \
    && adduser --system --uid 10001 --ingroup meguri --home /app meguri

COPY pyproject.toml README.md ./
COPY adapters ./adapters
COPY configs ./configs
COPY services ./services

RUN python -m pip install --upgrade pip \
    && python -m pip install . \
    && mkdir -p /app/datasets/meguri /var/log/meguri /run/meguri \
    && chown -R meguri:meguri /app /var/log/meguri /run/meguri

USER 10001:10001

EXPOSE 8000

CMD ["python", "-m", "uvicorn", "services.meguri_core.app:app", "--host", "0.0.0.0", "--port", "8000"]
