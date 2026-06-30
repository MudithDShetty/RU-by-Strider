# Device gate — INT8 backend verification

Run after installing a debug build with bundled `model_int8.onnx`.

## 1. Clear stale state (first run or after model bump)

```bash
adb shell pm clear com.strider.quanto
```

## 2. Install and open app

Grant **All files access** when prompted, then tap **Index**.

## 3. Logcat checks

```bash
adb logcat -s EmbeddingEngine EmbeddingBackend ModelAssetDelivery IndexingWorker
```

| Signal | Pass | Fail |
|--------|------|------|
| Model size | ~97858099 bytes (~93 MB) | ~390000000 bytes (FP32) |
| Backend | `int8_cpu` or `int8_nnapi` | `fp32_cpu` |
| First index | Scan count rises; files indexed > 0 | Stays 0 with permission granted |

## 4. Eval export

```bash
adb pull /data/data/com.strider.quanto/files/ru_eval ./analytics_data
```

Check `index_completed` for `"embedding_backend": "int8_cpu"` and `search_completed` for `"embedding_cache_warm": true` after index.

## 5. Performance baseline

Full index ~23k files: target **embedding phase** ~6–12 min (INT8) vs ~25 min (FP32). Total wall time depends on PDF/metadata mix.
