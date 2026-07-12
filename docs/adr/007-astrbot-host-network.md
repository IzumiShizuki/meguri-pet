# ADR 007: AstrBot host network coexistence

Status: accepted.

Never deploy a second AstrBot or alter its host network. The future gateway plugin calls a loopback-bound Meguri endpoint such as `127.0.0.1:8100`. The Meguri container remains on a bridge network and must not publish the core on `0.0.0.0` for this integration.

