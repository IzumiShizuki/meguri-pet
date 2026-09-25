FROM mcr.microsoft.com/openjdk/jdk:21-ubuntu

RUN groupadd --system --gid 10001 meguri \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home meguri

WORKDIR /app
COPY target/meguri-core-0.1.0-SNAPSHOT.jar /app/meguri-core.jar
COPY configs /app/configs

RUN mkdir -p /app/datasets/meguri /run/meguri \
    && chown -R 10001:10001 /app /run/meguri

USER 10001:10001
EXPOSE 18080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/meguri-core.jar"]
