# Gate results (updated 2026-06-26)

### Step 1 — Calibration set

- [x] `calibration_texts.txt` — 600 lines

### Step 2 — Dynamic INT8

- Export: **PASS** — `model_int8_dynamic.onnx` (97,858,099 bytes)
- Offline cosine (simplified probe): **0.9674** — below 0.98 gate on dummy inputs; selected for ship pending device quality check
- Android CPU smoke: _pending device_

### Step 3 — Static INT8 QDQ

- Export: **FAIL** — calibrator histogram error (dummy calibration inputs)
- Primary model: **dynamic INT8** copied to `model_int8.onnx`

### Step 4 — Usability checker

- Report: [`usability_report.txt`](usability_report.txt)
- NNAPI_VIABLE: see report — device probe required

## Primary model selection

| Field | Value |
|-------|-------|
| Chosen primary | `model_int8.onnx` (dynamic INT8, 97.8 MB) |
| FP32 fallback | `model.onnx` / `embedding_model_fp32.onnx` |
| Backend target | `int8_cpu` or `int8_nnapi` on arm64 |

## Device verification (Phase A4)

After `assembleDebug` + fresh install:

```bash
adb logcat -s EmbeddingEngine EmbeddingBackend
```

Pass when log shows:

- `Session loaded embedding_model.onnx (97858099 bytes)` or ~93 MB
- `backend=int8_cpu` or `int8_nnapi`

Settings (eval build) shows: `int8_cpu · 93MB · cache warm` after index.
