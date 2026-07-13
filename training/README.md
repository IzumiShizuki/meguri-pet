# Meguri Training Workflow

All commands run from `D:\program\meguri-pet` and use the existing local environments.

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m training.verify_inputs
D:\environment\anaconda3\envs\py314\python.exe -m training.environment_inventory
D:\environment\anaconda3\envs\py314\python.exe -m training.tts_acoustic_gate --workers 8
D:\environment\anaconda3\envs\py314\python.exe -m training.text_baseline --provider mock
```

The acoustic command creates `reports/tts_quality_review.csv`. Listen to the 100 rows without changing the formal dataset, fill `manual_status` with `pass` or `reject`, and rerun:

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m training.tts_acoustic_gate --finalize-only
```

An interactive Windows helper is also available:

```powershell
powershell -ExecutionPolicy Bypass -File .\training\manual_review.ps1
```

Only `GO` or `CONDITIONAL_GO` allows isolated GPT-SoVITS workspace preparation:

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m training.prepare_gpt_sovits
```

The guarded runner then exposes explicit stages. Without `--execute` it only writes an orchestration plan; with `--execute` it runs GPU preprocessing or training and records stage logs. It still refuses to run unless both input verification and the acoustic Gate pass.

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m training.run_gpt_sovits_baseline --stage preprocess --execute
D:\environment\anaconda3\envs\py314\python.exe -m training.run_gpt_sovits_baseline --stage gpt --execute
D:\environment\anaconda3\envs\py314\python.exe -m training.run_gpt_sovits_baseline --stage sovits --execute
D:\environment\anaconda3\envs\py314\python.exe -m training.select_zero_shot_reference
```

The active voice scope is Japanese only. Chinese samples that already exist are historical smoke-test artifacts and are excluded from all future generation, tuning, and release decisions.

After the small baseline and fixed Japanese samples exist, finalize and verify the deliverables:

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m training.finalize_tts_baseline
D:\environment\anaconda3\envs\py314\python.exe -m training.verify_deliverables
```

`finalize_tts_baseline` creates a balanced, randomized A/B package. Its default review paths point to pair-level RMS-matched copies so output loudness does not reveal the model. It refuses to overwrite a review that already contains ratings. Do not open `reports/tts_blind_ab_key.json` before listening.

```powershell
powershell -ExecutionPolicy Bypass -File .\training\manual_compare_finetuned.ps1
D:\environment\anaconda3\envs\py314\python.exe -m training.score_blind_review
```

After each pair the helper offers replay controls (`A`, `B`, `R`), a score action (`N`), and quit (`Q`). The scoring rule is committed before listening: at least 8 fine-tuned wins, a win margin of at least 4, a mean blind-score improvement of at least 0.25, and zero severe fine-tuned issues. Passing it only creates a Go candidate; full training still requires explicit user approval.

The text harness defaults to a Mock Provider. For a real OpenAI-compatible endpoint, set `MEGURI_LLM_BASE_URL`, `MEGURI_LLM_API_KEY` and `MEGURI_LLM_MODEL` in the process environment and run the same fixed cases. Keys are never written to reports.

No command in this package modifies `datasets/meguri`, downloads models, starts a long training job, uploads artifacts, or enables TTS for AstrBot, the website or a cloud server.
