# Server-side secret file contract

No real secret belongs in this repository or in a committed env file. Compose
loads one environment-specific file for each secret and mounts it at
`/run/secrets/<name>`.

Required files per environment:

| File | Consumer | Content |
| --- | --- | --- |
| `postgres-password.txt` | PostgreSQL | Password for that environment's PostgreSQL bootstrap user |
| `database-url.txt` | Core and migration jobs | Complete environment-specific SQLAlchemy/asyncpg URL |
| `llm-api-key.txt` | Core | Candidate or last-good provider credential; may be an explicit development placeholder only when provider=`mock` |
| `jwt-secret.txt` | Core | Environment-specific signing secret |
| `astrbot-shared-token.txt` | Core/gateway | Environment-specific gateway token |

Expected server layout:

```text
/opt/meguri/<environment>/secrets/
```

The directory must be readable only by the deployment owner, and files should
use mode `0600`. Dev, staging, and production files must be generated
independently. Do not copy production values into another environment.

Applications consume the `_FILE` variables. A compatibility loader may expose
the value to an in-process provider, but it must never log the value or copy it
into a Release Manifest.

