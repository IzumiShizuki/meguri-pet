# ADR 007: AstrBot host network coexistence

Status: accepted.

Never deploy a second AstrBot or alter its host network. The gateway plugin calls the loopback-bound Java Core at `127.0.0.1:18080` by default. The Meguri container remains on a bridge network and must not publish the core on `0.0.0.0` for this integration. Remote device commands go through the separately authenticated Meguri Relay contract; AstrBot never connects directly to a home computer or Codex process.
