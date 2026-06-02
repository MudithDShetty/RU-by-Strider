# Strider Quanto — Engineering Tasks

## Problem 1: Model Loads Every App Open (CRITICAL UX)

### What's happening

`EmbeddingEngine.initialize()` is called in `MainActivity.onCreate()` every single time the app comes to foreground. The 390MB ONNX model is copied from assets → cache and a new `OrtSession` is created each time. This takes 4-8 seconds on mid-range hardware, completely defeating the purpose of a search utility app.

### Root cause

The `OrtSession` object lives inside `EmbeddingEngine` which is scoped to `MainActivity`. When the activity is destroyed (user switches apps, phone sleeps), the session is closed in `onDestroy()` and recreated next open.

### What needs to be done

**1a. Move EmbeddingEngine into a persistent Application class**

Create `StriderApp.kt` extending `Application`. Initialize the engine once here. Register it in `AndroidManifest.xml` with `android:name=".StriderApp"`. MainActivity should call `(application as StriderApp).engine` — never initialize itself.

```kotlin
class StriderApp : Application() {
    lateinit var engine: EmbeddingEngine
    lateinit var indexer: FileIndexer

    override fun onCreate() {
        super.onCreate()
        // Initialize in background once, stays alive for app process lifetime
        applicationScope.launch(Dispatchers.IO) {
            engine = EmbeddingEngine(this@StriderApp)
            engine.initialize()
            indexer = FileIndexer(engine)
        }
    }
}

```

**1b. Cache the model file check properly** The current code already copies to `cacheDir` but checks `modelFile.exists()` — this is correct but `cacheDir` can be cleared by Android. Use `filesDir` instead (not cleared automatically):

```kotlin
val modelFile = File(context.filesDir, "embedding_model.onnx")

```

**1c. Do NOT close the session in onDestroy** Remove `engine.close()` from `MainActivity.onDestroy()`. The Application class manages the lifecycle. The session should stay open for the entire process lifetime.

**1d. Show a one-time loading screen only on first install** Use `SharedPreferences` to track whether the model has been copied:

```kotlin
val prefs = getSharedPreferences("strider_prefs", MODE_PRIVATE)
val modelReady = prefs.getBoolean("model_copied", false)

```

On first launch: show full-screen loading UI with progress. On subsequent launches: engine is already warm in Application, show app immediately.

**1e. Keep OrtSession warm with a dummy inference** After initialization, run one dummy embed() call with empty string so the JIT-compiled inference path is warm and first real search is instant:

```kotlin
engine.embed("warmup") // throwaway call after init

```

### Expected result

- First install: one-time 4-8s loading screen shown explicitly
- Every subsequent open: app ready instantly, no loading at all
- Session persists across activity back-stack, screen off, app switching

---

## Problem 2: Indexing Is Too Slow (PERFORMANCE)

### What's happening

Current indexer processes files sequentially — one file at a time. Each file requires:

1. Building embedding text string
2. BPE tokenization (~5ms)
3. ONNX inference (~200-800ms on low-end CPU)
4. Storing result

For 1000 files this means 200-800 seconds (3-13 minutes). Unacceptable.

### Research findings on embedding speed optimization

**Finding 1: Batched inference is 3-5x faster than sequential** ONNX Runtime supports batched inputs. Instead of shape `[1, seq_len]`, use `[batch_size, seq_len]`. Process 8-16 files at once in a single model forward pass. Tradeoff: all sequences in a batch must be padded to the same length (longest in batch). For short filenames (avg ~10 tokens) this overhead is negligible.

**Finding 2: Smaller token length for filename-only indexing** Filenames rarely exceed 20 tokens. Using MAX_SEQ_LEN=512 is wasteful. For Phase 1 (filename indexing), use MAX_SEQ_LEN=64. This reduces inference time by ~60% with zero quality loss for short text.

**Finding 3: Parallel tokenization + serial inference** Tokenization is CPU-bound but fast (~5ms). Inference is the bottleneck. Use a producer-consumer pattern:

- Coroutine A: tokenizes files and fills a Channel
- Coroutine B: reads batches from Channel and runs inference This keeps the inference pipeline fed without gaps.

**Finding 4: Skip already-indexed files** Store a hash (last-modified timestamp + file size) alongside each embedding. On re-index, skip files whose hash hasn't changed. First index: slow. Every subsequent index: only processes new/changed files.

**Finding 5: Index priority tiers** Not all files are equally important to index first. Process in this order:

- Tier 1 (index first): Documents folder, Downloads folder, recent files
- Tier 2: DCIM/Camera, WhatsApp media
- Tier 3: Everything else This means useful results appear within 30 seconds even if full index takes longer.

### What needs to be done

**2a. Implement batched inference in EmbeddingEngine**

```kotlin
fun embedBatch(texts: List<String>): List<FloatArray> {
    val batchSize = texts.size
    val encoded = texts.map { tokenizer.encode(it, MAX_SEQ_LEN_SHORT) }
    val maxLen = encoded.maxOf { it.inputIds.size }

    // Pad all sequences to maxLen
    val inputIds = LongArray(batchSize * maxLen)
    val attentionMask = LongArray(batchSize * maxLen)
    val tokenTypeIds = LongArray(batchSize * maxLen)

    for ((i, enc) in encoded.withIndex()) {
        for ((j, id) in enc.inputIds.withIndex()) {
            inputIds[i * maxLen + j] = id
            attentionMask[i * maxLen + j] = enc.attentionMask[j]
        }
        // Padding positions stay 0 (already initialized)
    }

    val shape = longArrayOf(batchSize.toLong(), maxLen.toLong())
    // ... run inference, return CLS vector for each item in batch
}

```

**2b. Reduce MAX_SEQ_LEN for filename indexing to 64** Add a constant `MAX_SEQ_LEN_SHORT = 64` used exclusively by FileIndexer. Keep `MAX_SEQ_LEN = 512` for query embedding (queries can be long sentences).

**2c. Implement incremental indexing with hash cache** Store index as a map of `filePath → IndexEntry(embedding, lastModified, fileSize)`. Persist to `filesDir/index.bin` using `ObjectOutputStream`. On startup, load existing index. During indexing, only process changed files.

```kotlin
data class IndexEntry(
    val path: String,
    val name: String,
    val extension: String,
    val sizeBytes: Long,
    val embedding: FloatArray,
    val lastModified: Long,  // for change detection
    val fileSize: Long       // for change detection
)

```

**2d. Priority-ordered directory scanning**

```kotlin
val PRIORITY_DIRS = listOf(
    "Documents", "Downloads", "DCIM/Camera",
    "WhatsApp/Media", "Pictures", "Music", "Movies"
)
// Scan priority dirs first, emit results immediately
// Then scan remaining dirs in background

```

**2e. Batch size tuning for Redmi 8A** Snapdragon 439 has 4 efficiency + 4 performance cores. Recommended batch size: 8 (fits in L2 cache, balances throughput vs latency). Make it configurable: `val BATCH_SIZE = 8`

### Expected result

- Batch inference: 3-5x speedup (8 files per forward pass)
- Short sequence length: 2-3x speedup for filename indexing
- Combined: 6-10x total speedup
- 1000 files: from 10 minutes → 60-90 seconds
- With incremental indexing: re-index in seconds after first run

---

## Problem 3: Indexing Must Run in Background (ARCHITECTURE)

### What's happening

Indexing runs in a `lifecycleScope` coroutine tied to `MainActivity`. When the user switches apps or locks the phone, the Activity is stopped and indexing pauses/dies. For large file systems (5000+ files) this means indexing never completes.

### What needs to be done

**3a. Create IndexingWorker using WorkManager**

WorkManager is Android's official solution for deferrable background work that must complete even if the app is closed. It handles:

- App process death and restart
- Doze mode and battery optimization
- Progress reporting back to UI
- Retry on failure

```kotlin
// Add to app/build.gradle:
implementation 'androidx.work:work-runtime-ktx:2.9.0'

class IndexingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val engine = (applicationContext as StriderApp).engine
        val indexer = (applicationContext as StriderApp).indexer

        // Report progress to UI via setProgress()
        setProgress(workDataOf("status" to "Starting indexer..."))

        indexer.indexDirectory(
            rootPath = Environment.getExternalStorageDirectory().absolutePath,
            onProgress = { msg ->
                setProgress(workDataOf("status" to msg, "count" to indexer.size))
            },
            onFileIndexed = { count ->
                setProgress(workDataOf("count" to count))
            }
        )

        // Persist index to disk when done
        indexer.saveIndex()

        return Result.success()
    }
}

```

**3b. Enqueue indexing work from StriderApp**

```kotlin
// In StriderApp, after engine is ready:
fun startBackgroundIndexing() {
    val request = OneTimeWorkRequestBuilder<IndexingWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiresBatteryNotLow(true) // Don't drain battery
                .build()
        )
        .build()

    WorkManager.getInstance(this)
        .enqueueUniqueWork(
            "file_indexing",
            ExistingWorkPolicy.KEEP, // Don't restart if already running
            request
        )
}

```

**3c. Show a persistent notification during indexing**

Users need to know indexing is happening. WorkManager supports foreground service mode for long-running tasks:

```kotlin
// In IndexingWorker.doWork():
setForeground(
    ForegroundInfo(
        NOTIFICATION_ID,
        buildNotification("Strider Quanto: Indexing files...", count)
    )
)

```

Notification should show:

- "Indexing files... (342 / ~1000)"
- Small progress indicator
- Tap to open app

**3d. Observe work progress in MainActivity**

```kotlin
// In MainActivity, observe live progress:
WorkManager.getInstance(this)
    .getWorkInfosForUniqueWorkLiveData("file_indexing")
    .observe(this) { workInfos ->
        val info = workInfos.firstOrNull() ?: return@observe
        val status = info.progress.getString("status") ?: return@observe
        val count = info.progress.getInt("count", 0)
        binding.tvStatus.text = status
        binding.tvIndexCount.text = "$count files indexed"

        if (info.state == WorkInfo.State.SUCCEEDED) {
            binding.tvStatus.text = "✓ Index complete"
        }
    }

```

**3e. Trigger re-indexing smartly** Don't re-index every app open. Use these triggers instead:

- First install: index immediately
- Every 24 hours: check for changed files only (incremental)
- User explicitly taps "Re-index": full re-index

```kotlin
val periodicRequest = PeriodicWorkRequestBuilder<IndexingWorker>(
    24, TimeUnit.HOURS
).build()

WorkManager.getInstance(this).enqueueUniquePeriodicWork(
    "periodic_indexing",
    ExistingPeriodicWorkPolicy.KEEP,
    periodicRequest
)

```

**3f. Persist index to disk so it survives app restarts**

```kotlin
// FileIndexer additions:
fun saveIndex() {
    val file = File(context.filesDir, "search_index.bin")
    ObjectOutputStream(file.outputStream()).use { it.writeObject(index) }
}

fun loadIndex(): Boolean {
    val file = File(context.filesDir, "search_index.bin")
    if (!file.exists()) return false
    return try {
        val loaded = ObjectInputStream(file.inputStream()).use {
            it.readObject() as MutableList<IndexEntry>
        }
        index.clear()
        index.addAll(loaded)
        true
    } catch (e: Exception) { false }
}

```

### Expected result

- User taps "Index Files" → background work starts → user can close app
- Notification shows live progress: "Indexing... 342 files"
- App re-opened: shows current index count, search works immediately
- Phone locked overnight: indexing completes by morning
- Next day: only new/changed files re-indexed (seconds, not minutes)

---

## Implementation Order

Do these in sequence — each builds on the previous:

```
Step 1: Problem 1 fixes (Application class + filesDir + no close on destroy)
        → Verify: reopen app 5 times, model loads instantly after first open

Step 2: Problem 3a + 3f (WorkManager + disk persistence)
        → Verify: start indexing, close app, reopen, index continues/completes

Step 3: Problem 2a + 2b (batched inference + short seq len)
        → Benchmark: time 100 files before and after, confirm 5x+ speedup

Step 4: Problem 2d + 3c (priority dirs + notification)
        → Verify: Documents/Downloads indexed within 30 seconds of starting

Step 5: Problem 2c + 3e (incremental indexing + periodic work)
        → Verify: second index run takes <10 seconds for unchanged files

```

---

## Files To Modify


| File                  | Changes                                                                                           |
| --------------------- | ------------------------------------------------------------------------------------------------- |
| `StriderApp.kt`       | CREATE — Application subclass, owns engine + indexer lifecycle                                    |
| `AndroidManifest.xml` | Add `android:name=".StriderApp"`, add WorkManager foreground service permission                   |
| `EmbeddingEngine.kt`  | Add `embedBatch()`, change `cacheDir` → `filesDir`, remove close-on-destroy pattern               |
| `FileIndexer.kt`      | Add `saveIndex()`, `loadIndex()`, `IndexEntry` with hash, priority dir ordering, batch processing |
| `IndexingWorker.kt`   | CREATE — WorkManager CoroutineWorker, foreground notification, progress reporting                 |
| `MainActivity.kt`     | Remove engine init, observe WorkManager progress, load persisted index on start                   |
| `app/build.gradle`    | Add `work-runtime-ktx:2.9.0` dependency                                                           |


---

## Dependencies To Add to app/build.gradle

```gradle
// WorkManager — background indexing
implementation 'androidx.work:work-runtime-ktx:2.9.0'

// Startup — initialize engine before first activity
implementation 'androidx.startup:startup-runtime:1.1.1'

```

## Permissions To Add to AndroidManifest.xml

```xml
<!-- WorkManager foreground service for background indexing -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- WorkManager service declaration -->
<service
    android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />

```

