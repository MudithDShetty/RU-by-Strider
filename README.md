<<<<<<< HEAD
# Strider Quanto — On-device semantic file search

## Before building: place model files

Copy from your `model_assets/` folder:

```
model_assets/onnx/model.onnx         →  app/src/main/assets/embedding_model.onnx
model_assets/tokenizer.json          →  app/src/main/assets/tokenizer.json
model_assets/tokenizer_config.json   →  app/src/main/assets/tokenizer_config.json
```

Windows PowerShell commands:
```powershell
Copy-Item "model_assets\onnx\model.onnx" "app\src\main\assets\embedding_model.onnx"
Copy-Item "model_assets\tokenizer.json" "app\src\main\assets\tokenizer.json"
Copy-Item "model_assets\tokenizer_config.json" "app\src\main\assets\tokenizer_config.json"
```

## Build in Android Studio

1. Open Android Studio → Open → select this folder
2. Wait for Gradle sync to complete
3. Build → Make Project
4. Run → Run 'app' (with Redmi 8A connected via USB)

## First run on the phone

1. Tap "Index Files" — grant storage permission when prompted
2. Wait for indexing (~1-3 minutes for 500 files)
3. Type any query in the search box and tap Search
4. Tap a result to open the file

## Architecture

```
BpeTokenizer.kt      Reads tokenizer.json, encodes text → token IDs
EmbeddingEngine.kt   ONNX Runtime session, runs granite-embedding-97m
FileIndexer.kt       Walks storage, builds in-memory embedding index
MainActivity.kt      UI controller
ResultsAdapter.kt    RecyclerView for search results
```
=======
# RU-by-Strider
>>>>>>> 8e1300082ef8ceaf304669b2d20d4fe38d33a22d
