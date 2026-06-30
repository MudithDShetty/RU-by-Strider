# Step 10 — Stability matrix (device checklist)

Run on **two arm64 devices** before release merge. All runs must complete without native crash (`SIGSEGV`, `SIGBUS`) or ANR.

| Run | Scenario | Guardrails | Pass criteria |
|-----|----------|------------|---------------|
| 1 | Cold install → full index (~18k files) | G2, G4, G7 | Completes; `embedding_backend` logged; no crash |
| 2 | Kill app mid-index → relaunch | G8, G11 | Worker resumes or clean retry; no duplicate corruption |
| 3 | Search during/after index | G8 | Golden query "nda quantoo" returns results |
| 4 | Low memory (background apps) | G2, G5 | Batch shrinks to 8/4; cooldown triggers; no OOM kill |
| 5 | OS kill during index | G8, G11 | Next launch skips NNAPI if crash hint set (G10) |
| 6 | Force `PREF_LAST_BACKEND_CRASHED=true` | G10 | NNAPI skipped; INT8_CPU or FP32_CPU only |
| 7 | Release build R8 smoke test | — | Cold start → index → search → OCR one PDF without crash |
| 8 | FP32 on-demand fallback | — | Force quality escalation → on-demand pack → re-index on FP32 |

## adb helpers

```bash
adb logcat -s EmbeddingEngine EmbeddingBackend EmbeddingGuardrails FileIndexer ModelAssetDelivery
adb shell run-as com.strider.quanto cat shared_prefs/strider_quanto.xml
```

## Eval export

```bash
adb pull /data/data/com.strider.quanto/files/ru_eval ./analytics_data
```

Record pass/fail per device in this file after each run.
