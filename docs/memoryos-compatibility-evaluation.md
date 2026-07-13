# Existing MemoryOS compatibility evaluation

Evaluation date: 2026-07-13 (Asia/Shanghai)

## Evidence

- Deployed wrapper source inspected read-only at `D:\program\shizuki-site\apps\memoryos-service\app.py`.
- Vendored upstream snapshot: `BAI-LAB/MemoryOS@8688d5128901a88a70a3ba961de8705a6cdab4c0`.
- Deployment definition: `D:\program\shizuki-site\deploy\docker-compose.memoryos.yml`.
- Production `GET http://111.228.35.186:8788/health` returned HTTP 502 from the current development environment. No scope, journal or record endpoint was called.

## API findings

| Capability | Existing wrapper | Adapter decision |
| --- | --- | --- |
| Health | `GET /health` | Supported, read-only |
| Append conversation | `POST .../records` | Supported for isolated shadow tests only |
| Retrieve | `POST .../retrieve` | Supported and mapped to `MemoryHit` |
| Profile | `GET .../profile` | Not used by default because it initializes scope storage |
| Journal | `GET .../journal` | Mapped to limited export/list behavior |
| Generate response | `POST .../respond` | Forbidden; invokes MemoryOS internal LLM |
| Stable record ID | Missing | Synthetic IDs are observation-only |
| Update/supersede | Missing | Explicitly unsupported |
| Delete | Missing | Explicitly unsupported |
| Authentication | No application-layer check in wrapper | High-risk; optional Bearer support reserved in adapter |

## Test matrix

| Requirement | Result |
| --- | --- |
| User/scope isolation | Offline pass: HMAC scope per `meguri_user_id` |
| Multi-client formal-memory sharing | Contract pass when clients share the same bound user ID |
| Retrieval mapping | Offline pass for episodic and user knowledge |
| Update/conflict | Not compatible; no upstream update API |
| Delete/export | Delete unavailable; journal export is incomplete and synthetic |
| Concurrent requests | Offline pass; deployed wrapper also serializes per scope lock |
| Restart recovery | File-backed by source inspection; restoration has not been live-tested |
| API failure | Offline pass; failures do not block Meguri text turns |
| No-auth risk | Confirmed in wrapper source; live health was not reachable from this environment |
| Internal LLM avoidance | Pass: adapter never calls `/respond` |

## Decision

`ExistingMemoryOSAdapter` is **not eligible as Meguri's authoritative MemoryProvider**. It may be used only in a user-approved, isolated shadow scope after confirming that no other production consumer owns the instance. `FakeMemoryProvider` remains the local default. A provider with stable IDs, versioned supersede, deletion and restoration guarantees is still required for production.

No existing MemoryOS data, volume, process, network or configuration was changed during this evaluation.
