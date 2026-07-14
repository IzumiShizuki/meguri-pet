# Memory backup restore validation

The environment workflow owns backup creation and isolated staging restore. After restore, run the read-only domain validator against the temporary database before cleanup:

```powershell
$env:MEGURI_ENV = 'staging'
$env:MEGURI_DATABASE_URL_FILE = 'C:\absolute\path\to\restored-app-url.txt'
$env:MEGURI_DATABASE_REVISION = '20260714_0004'
& 'D:\environment\anaconda3\envs\py314\python.exe' scripts\validate_memory_recovery.py
```

The validator emits JSON and exits non-zero on revision drift, invalid current-version pointers, active items without versions, ready-embedding hash mismatch or audit replay mismatch. It reports table counts for all nine required tables and never prints the database URL or memory content.

The staging restore rehearsal must additionally record archive checksum/size, PostgreSQL and pgvector versions, source and isolated target identities, start/end times, cleanup proof, RPO and RTO, and a fixed recall-corpus result. Production restore is not authorized by this runbook.
