# Existing MemoryOS adapter

This adapter targets the existing `shizuki-site/apps/memoryos-service/app.py` wrapper at upstream snapshot `8688d5128901a88a70a3ba961de8705a6cdab4c0`.

It is intentionally a shadow/evaluation adapter. The current wrapper supports record append, retrieval, profile and journal reads, but exposes no stable record ID, update, supersede or delete operation. `respond` is never used because it invokes MemoryOS's internal LLM. The default URL is loopback-only and production access is not enabled by this repository.
