$python = "D:\environment\anaconda3\envs\py314\python.exe"
& $python -m uvicorn services.meguri_core.app:app --host 127.0.0.1 --port 8000
