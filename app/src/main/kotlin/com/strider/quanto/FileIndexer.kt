package com.strider.quanto

import android.util.Log
import com.strider.quanto.eval.EvalLogger
import com.strider.quanto.eval.IndexPhaseTracker
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

data class IndexedFileStub(
    val path: String,
    val name: String,
    val extension: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val categories: List<Category>,
    val metadata: FileMetadata
) {
    val displaySize: String get() = when {
        sizeBytes < 1024             -> "${sizeBytes}B"
        sizeBytes < 1024 * 1024      -> "${sizeBytes / 1024}KB"
        else -> "${"%.1f".format(sizeBytes / (1024.0 * 1024))}MB"
    }
}

data class IndexedFile(
    val path: String,
    val name: String,
    val extension: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val embedding: FloatArray,
    val categories: List<Category>,
    val metadata: FileMetadata
) {
    val displaySize: String get() = when {
        sizeBytes < 1024             -> "${sizeBytes}B"
        sizeBytes < 1024 * 1024      -> "${sizeBytes / 1024}KB"
        else -> "${"%.1f".format(sizeBytes / (1024.0 * 1024))}MB"
    }

    companion object {
        fun fromStub(stub: IndexedFileStub, embedding: FloatArray) = IndexedFile(
            path         = stub.path,
            name         = stub.name,
            extension    = stub.extension,
            sizeBytes    = stub.sizeBytes,
            lastModified = stub.lastModified,
            embedding    = embedding,
            categories   = stub.categories,
            metadata     = stub.metadata
        )
    }
}

data class IndexDirectoryResult(
    val indexedCount: Int,
    val skippedCount: Int,
    val deletedCount: Int,
    val ocrPendingCount: Int
)

class FileIndexer(
    private val engine: EmbeddingEngine,
    private val db: DatabaseHelper,
    private val appContext: android.content.Context
) {

    companion object {
        private const val TAG = "FileIndexer"

        private val SUPPORTED_EXTENSIONS_ORDERED = listOf(
            "txt", "md", "pdf", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx",
            "csv", "json", "xml", "html", "htm",
            "py", "js", "ts", "kt", "java", "cpp", "c", "h",
            "jpg", "jpeg", "png", "webp", "heic", "gif",
            "mp3", "aac", "flac", "wav", "m4a",
            "mp4", "mkv", "avi", "mov",
            "zip", "rar", "7z", "tar", "gz", "apk"
        )

        private val SUPPORTED_EXTENSIONS = SUPPORTED_EXTENSIONS_ORDERED.toSet()

        private val SKIP_DIRS = setOf(
            "Android/data", "Android/obb", ".thumbnails",
            ".cache", "lost+found", ".trash"
        )

        /** Index these folders first so useful results appear within ~30s. */
        private val PRIORITY_DIRS = listOf(
            "Documents", "Download", "Downloads", "DCIM", "Camera",
            "WhatsApp", "Media", "Pictures", "Music", "Movies"
        )

        /** Batch size tuned for 4-core mid-range ARM; adaptive via MemoryProbe. */
        private const val BATCH_SIZE_DEFAULT = 16
        private const val BATCH_SIZE_MIN = 4

        /** Bulk index: thermal guard applies dynamic cooldown; OCR keeps fixed pause. */
        private const val OCR_SESSION_CAP = 200
        private const val OCR_COOLDOWN_MS = 400L
        private const val OCR_EMBED_COOLDOWN_MS = 120L
    }

    private val searchLock = Any()

    /** Total indexed files — always read from SQLite, never held in RAM. */
    val size: Int get() = db.getTotalCount()

    fun getBucketSizes(): Map<Category, Int> = db.getCategoryCounts()

    private var deferredFtsPaths: List<String>? = null

    fun loadFromDatabase(deferFtsBackfill: Boolean = false) {
        val count = db.getTotalCount()
        if (deferFtsBackfill && count > 0) {
            deferredFtsPaths = db.loadAllPaths()
        } else if (count > 0) {
            backfillFtsKeywords(db.loadAllPaths())
        }
        RuLog.d(TAG) { "Index ready — $count files (disk-backed search)" }
        RuLog.d(TAG) { "Category counts: ${getBucketSizes()}" }
    }

    /** Runs FTS backfill deferred from startup so the UI is not blocked. */
    fun runDeferredFtsBackfill() {
        val paths = deferredFtsPaths ?: return
        deferredFtsPaths = null
        backfillFtsKeywords(paths)
    }

    fun buildKeywordString(
        file: File,
        contentSnippet: String?,
        metadata: FileMetadata,
        pdfMeta: Map<String, String> = emptyMap()
    ): String {
        val parts = mutableListOf<String>()
        val entities = extractFilenameEntities(file.nameWithoutExtension)

        parts.add(file.nameWithoutExtension.replace(Regex("[_\\-.]"), " "))
        parts.add(file.extension.lowercase())
        file.parentFile?.name?.let { parts.add(it) }
        parts.addAll(metadata.categories.map { it.label })
        contentSnippet?.let { parts.add(it) }
        parts.add(metadata.typeLabel)

        entities.personName?.let { parts.add(it) }
        parts.add(OwnerMatcher.primaryTokensForFts(metadata.ownerEntities))
        metadata.ownerEntities.filter { it.role !in setOf(NameRole.PRIMARY, NameRole.CO_PRIMARY) }
            .flatMap { it.tokens }
            .distinct()
            .forEach { parts.add(it) }
        metadata.extractedEntities.forEach { entity ->
            parts.add("entity:$entity")
            parts.add(entity)
        }
        entities.month?.let { parts.add(it) }
        entities.year?.let { parts.add(it) }
        entities.quarter?.let { parts.add(it) }
        entities.documentNumber?.let { parts.add(it) }
        entities.version?.let { parts.add(it) }

        if (file.extension.lowercase() == "pdf" && pdfMeta.isNotEmpty()) {
            parts.addAll(pdfMeta.values)
        } else if (file.extension.lowercase() == "pdf" && metadata.pdfMetadata.isNotEmpty()) {
            parts.addAll(metadata.pdfMetadata.values)
        } else if (file.extension.lowercase() == "pdf") {
            parts.addAll(extractPdfMetadata(file).values)
        }

        parts.add(MultilingualBridge.indexKeywordExtras(file.nameWithoutExtension, contentSnippet))

        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    suspend fun indexDirectory(
        rootPath: String,
        source: String = "manual",
        scanDocumentText: Boolean = true,
        forceFull: Boolean = false,
        phaseTracker: IndexPhaseTracker? = null,
        onProgress: suspend (String) -> Unit,
        onFileIndexed: suspend (Int, Int, Int) -> Unit
    ): IndexDirectoryResult = withContext(Dispatchers.IO) {
        val indexStartMs = System.currentTimeMillis()

        if (!EmbeddingGuardrails.acquireIndexing()) {
            Log.w(TAG, "Index skipped — heavy work mutex busy (${EmbeddingGuardrails.heavyWorkState})")
            onProgress("Indexing paused — another task is running. Retrying…")
            throw IllegalStateException("Heavy work mutex busy: ${EmbeddingGuardrails.heavyWorkState}")
        }

        try {
            engine.setIndexingMode(true)
            indexDirectoryInternal(
                rootPath, source, scanDocumentText, forceFull, phaseTracker,
                indexStartMs, onProgress, onFileIndexed
            )
        } finally {
            engine.setIndexingMode(false)
            EmbeddingGuardrails.releaseIndexing()
        }
    }

    private suspend fun indexDirectoryInternal(
        rootPath: String,
        source: String,
        scanDocumentText: Boolean,
        forceFull: Boolean,
        phaseTracker: IndexPhaseTracker?,
        indexStartMs: Long,
        onProgress: suspend (String) -> Unit,
        onFileIndexed: suspend (Int, Int, Int) -> Unit
    ): IndexDirectoryResult {

        val root = File(rootPath)
        if (!root.exists() || !root.canRead()) {
            onProgress("Cannot read $rootPath — check storage permissions")
            throw SecurityException("Storage not readable: grant full file access and retry")
        }

        phaseTracker?.begin("disk_scan")

        val storedMeta = db.getStoredFileMeta()
        RuLog.d(TAG) { "Stored in DB: ${storedMeta.size} files" }

        onProgress("Scanning storage for files… (this can take a few minutes)")

        val allDiskFiles = mutableListOf<File>()
        var lastScanReportMs = System.currentTimeMillis()
        val walker = root.walkTopDown()
            .onEnter { dir ->
                val path = dir.absolutePath
                SKIP_DIRS.none { s -> path.contains(s) }
            }
        for (node in walker) {
            if (node.isFile && node.extension.lowercase() in SUPPORTED_EXTENSIONS) {
                allDiskFiles.add(node)
            }
            val now = System.currentTimeMillis()
            if (now - lastScanReportMs >= 2_000L) {
                lastScanReportMs = now
                onProgress("Scanning storage… ${allDiskFiles.size} files found so far")
            }
        }

        onProgress("Found ${allDiskFiles.size} supported files — indexing all…")

        val diskFiles = allDiskFiles.sortedWith(
            compareBy<File>(
                { filePriority(it) },
                { file ->
                    val idx = SUPPORTED_EXTENSIONS_ORDERED.indexOf(file.extension.lowercase())
                    if (idx == -1) Int.MAX_VALUE else idx
                }
            )
        )

        RuLog.d(TAG) { "Files on disk: ${diskFiles.size} — indexing all supported files" }

        val diskPaths = diskFiles.map { it.absolutePath }.toSet()
        val deletedPaths = storedMeta.keys.filter { it !in diskPaths }
        if (deletedPaths.isNotEmpty()) {
            db.deleteFiles(deletedPaths)
            RuLog.d(TAG) { "Deleted ${deletedPaths.size} stale records" }
        }

        val toIndex = if (forceFull) {
            diskFiles
        } else {
            diskFiles.filter { file ->
                val stored = storedMeta[file.absolutePath]
                when {
                    stored == null -> true
                    stored.first != file.lastModified() -> true
                    stored.second != file.length() -> true
                    else -> false
                }
            }
        }

        RuLog.d(TAG) { "To index: ${toIndex.size}, Skipped (unchanged): ${diskFiles.size - toIndex.size}" }

        val skippedCount = if (forceFull) 0 else diskFiles.size - toIndex.size

        phaseTracker?.end("disk_scan", fileCount = toIndex.size)

        onProgress(
            if (toIndex.isEmpty()) {
                "All ${diskFiles.size} files up to date — nothing new to index"
            } else {
                "Indexing ${toIndex.size} files ($skippedCount unchanged)…"
            }
        )

        if (toIndex.isEmpty()) {
            return IndexDirectoryResult(
                indexedCount = 0,
                skippedCount = skippedCount,
                deletedCount = deletedPaths.size,
                ocrPendingCount = if (scanDocumentText) db.getOcrPendingCount() else 0
            )
        }

        if (toIndex.size >= 50 && db.isEmbeddingCacheWarm()) {
            RuLog.i(TAG) { "Bulk index — releasing embedding RAM cache to reduce memory pressure" }
            db.invalidateEmbeddingCache()
        }

        var indexedCount = 0

        var batchSize = EmbeddingGuardrails.MemoryProbe.recommendedBatchSize(appContext, BATCH_SIZE_DEFAULT)
        var batchIndex = 0
        var consecutiveSingleFileFailures = 0
        var successfulBatchesAtSize = 0

        val pending = toIndex.toMutableList()
        while (pending.isNotEmpty()) {
            val batch = pending.take(batchSize)
            try {
                onProgress("Indexing batch of ${batch.size}… (${db.getTotalCount()} total so far)")

                phaseTracker?.begin("metadata_extraction")
                val metadatas = batch.map { buildFileMetadata(it) }
                phaseTracker?.end("metadata_extraction", fileCount = metadatas.size)

                val pairs = batch.zip(metadatas).filter { (_, meta) ->
                    meta.metadataString.isNotBlank().also { ok ->
                        if (!ok) Log.w(TAG, "Skipping blank metadata")
                    }
                }
                if (pairs.isEmpty()) {
                    pending.subList(0, batch.size).clear()
                    batchIndex++
                    continue
                }

                val texts = pairs.map { it.second.metadataString }
                phaseTracker?.begin("embedding")
                val embeddings = try {
                    engine.embedBatch(texts)
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM on batch ${batch.size} — halving", e)
                    db.invalidateEmbeddingCache()
                    batchSize = (batchSize / 2).coerceAtLeast(BATCH_SIZE_MIN)
                    successfulBatchesAtSize = 0
                    throw e
                }
                phaseTracker?.end("embedding", fileCount = texts.size)

                phaseTracker?.begin("persist")
                pairs.forEachIndexed { i, (file, metadata) ->
                    val indexed = IndexedFile(
                        path         = file.absolutePath,
                        name         = file.name,
                        extension    = file.extension.lowercase(),
                        sizeBytes    = file.length(),
                        lastModified = file.lastModified(),
                        embedding    = embeddings[i],
                        categories   = metadata.categories,
                        metadata     = metadata
                    )

                    val ocrPending = scanDocumentText && metadata.pdfNeedsOcr
                    db.upsertFile(indexed, ocrPending)
                    val keywords = buildKeywordString(
                        file, metadata.contentSnippet, metadata, metadata.pdfMetadata
                    )
                    db.ftsInsert(file.absolutePath, keywords)
                    indexedCount++
                    onFileIndexed(indexedCount, skippedCount, deletedPaths.size)
                    RuLog.d(TAG) { "Indexed [${metadata.categories.joinToString { it.label }}] ${file.name}" }
                }
                phaseTracker?.end("persist", fileCount = pairs.size)

                pending.subList(0, batch.size).clear()
                consecutiveSingleFileFailures = 0
                successfulBatchesAtSize++
                if (successfulBatchesAtSize >= 10 && batchSize < BATCH_SIZE_DEFAULT) {
                    batchSize = (batchSize * 2).coerceAtMost(BATCH_SIZE_DEFAULT)
                    successfulBatchesAtSize = 0
                }

                val cooldown = EmbeddingGuardrails.ThermalGuard.batchCooldownMs(appContext, batchIndex)
                if (cooldown > 0) delay(cooldown)
                batchIndex++
            } catch (e: OutOfMemoryError) {
                if (batchSize > BATCH_SIZE_MIN) {
                    batchSize = (batchSize / 2).coerceAtLeast(BATCH_SIZE_MIN)
                    db.invalidateEmbeddingCache()
                    continue
                }
                Log.e(TAG, "OOM at minimum batch size", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "Batch failed, falling back to single-file: ${e.message}", e)
                for (file in batch) {
                    if (consecutiveSingleFileFailures >= 3) {
                        Log.e(TAG, "Skipping remainder of batch after 3 consecutive failures")
                        break
                    }
                    try {
                        onProgress("Indexing: ${file.name}")
                        phaseTracker?.begin("metadata_extraction")
                        val metadata = buildFileMetadata(file)
                        phaseTracker?.end("metadata_extraction", fileCount = 1)
                        if (metadata.metadataString.isBlank()) {
                            pending.remove(file)
                            continue
                        }
                        phaseTracker?.begin("embedding")
                        val embedding = engine.embed(metadata.metadataString)
                        phaseTracker?.end("embedding", fileCount = 1)

                        val indexed = IndexedFile(
                            path         = file.absolutePath,
                            name         = file.name,
                            extension    = file.extension.lowercase(),
                            sizeBytes    = file.length(),
                            lastModified = file.lastModified(),
                            embedding    = embedding,
                            categories   = metadata.categories,
                            metadata     = metadata
                        )

                        val ocrPending = scanDocumentText && metadata.pdfNeedsOcr
                        phaseTracker?.begin("persist")
                        db.upsertFile(indexed, ocrPending)
                        val keywords = buildKeywordString(
                            file, metadata.contentSnippet, metadata, metadata.pdfMetadata
                        )
                        db.ftsInsert(file.absolutePath, keywords)
                        phaseTracker?.end("persist", fileCount = 1)
                        indexedCount++
                        onFileIndexed(indexedCount, skippedCount, deletedPaths.size)
                        pending.remove(file)
                        consecutiveSingleFileFailures = 0
                    } catch (inner: Exception) {
                        consecutiveSingleFileFailures++
                        Log.e(TAG, "Failed to index ${file.name}: ${inner.message}", inner)
                    }
                }
                pending.subList(0, minOf(batch.size, pending.size)).clear()
                batchIndex++
            }
        }

        // Defer RAM warm — loading full matrix while ONNX is active causes OOM on large libraries.
        RuLog.d(TAG) { "Index ready — ${db.getTotalCount()} files (disk-backed search)" }
        val ocrPendingCount = if (scanDocumentText) db.getOcrPendingCount() else 0
        RuLog.d(TAG) { "Done — indexed:$indexedCount skipped:$skippedCount deleted:${deletedPaths.size} total:$size ocrPending:$ocrPendingCount" }
        if (EvalLogger.enabled) {
            val phases = phaseTracker?.snapshot().orEmpty()
            EvalLogger.logIndexPhases(phases, source)
            EvalLogger.logIndexCompleted(
                source = source,
                indexedCount = indexedCount,
                skippedCount = skippedCount,
                deletedCount = deletedPaths.size,
                totalFiles = size,
                durationMs = System.currentTimeMillis() - indexStartMs,
                categoryCounts = getBucketSizes(),
                phases = phases,
                embeddingBackend = engine.currentBackend.label,
                modelVersion = BuildConfig.MODEL_VERSION
            )
        }
        return IndexDirectoryResult(
            indexedCount = indexedCount,
            skippedCount = skippedCount,
            deletedCount = deletedPaths.size,
            ocrPendingCount = ocrPendingCount
        )
    }

    /** Re-index a single PDF after OCR — same embed/FTS path as main indexer. */
    suspend fun applyOcrAndReindex(file: File, ocrText: String, pdfBundle: PdfExtract? = null) =
        withContext(Dispatchers.IO) {
        val metadata = buildFileMetadata(file, ocrText, pdfBundle)
        if (metadata.metadataString.isBlank()) {
            db.clearOcrPending(file.absolutePath)
            return@withContext
        }
        val embedding = engine.embed(metadata.metadataString)
        if (OCR_EMBED_COOLDOWN_MS > 0) delay(OCR_EMBED_COOLDOWN_MS)
        val indexed = IndexedFile(
            path         = file.absolutePath,
            name         = file.name,
            extension    = file.extension.lowercase(),
            sizeBytes    = file.length(),
            lastModified = file.lastModified(),
            embedding    = embedding,
            categories   = metadata.categories,
            metadata     = metadata
        )
        db.upsertFile(indexed, ocrPending = false)
        val keywords = buildKeywordString(
            file, metadata.contentSnippet, metadata, metadata.pdfMetadata
        )
        db.ftsInsert(file.absolutePath, keywords)
        RuLog.d(TAG) { "OCR re-indexed ${file.name}" }
    }

    suspend fun processOcrPending(
        onProgress: suspend (String) -> Unit,
        maxFiles: Int = OCR_SESSION_CAP
    ): Int = withContext(Dispatchers.IO) {
        val paths = db.getOcrPendingPaths(maxFiles)
            .sortedBy { pathPriority(File(it)) }
        if (paths.isEmpty()) return@withContext 0

        var processed = 0
        paths.forEachIndexed { index, path ->
            val file = File(path)
            if (!file.exists()) {
                db.clearOcrPending(path)
                return@forEachIndexed
            }
            if (!PdfTextQuality.isOcrEligiblePath(file)) {
                db.clearOcrPending(path)
                return@forEachIndexed
            }
            val remaining = paths.size - index
            onProgress("Reading scanned documents… ($remaining remaining)")
            try {
                PDDocument.load(file).use { doc ->
                    val ocrText = DocumentOcr.ocrFromDocument(file, doc)
                    if (ocrText != null) {
                        val bundle = extractPdfBundleFromDocument(file, doc, ocrText)
                        applyOcrAndReindex(file, ocrText, bundle)
                    } else {
                        db.clearOcrPending(path)
                    }
                }
                processed++
            } catch (e: Exception) {
                Log.e(TAG, "OCR worker failed for ${file.name}: ${e.message}", e)
                db.clearOcrPending(path)
            }
            if (OCR_COOLDOWN_MS > 0) delay(OCR_COOLDOWN_MS)
        }
        processed
    }

    /** Populate FTS keywords in batches — paths only, no full stub load into memory. */
    private fun backfillFtsKeywords(paths: List<String>) {
        var count = 0
        for (chunk in paths.chunked(100)) {
            val stubs = db.loadStubsForPaths(chunk)
            for (stub in stubs) {
                val file = File(stub.path)
                if (!file.exists()) continue
                try {
                    val keywords = buildKeywordString(
                        file, stub.metadata.contentSnippet, stub.metadata, stub.metadata.pdfMetadata
                    )
                    db.ftsInsert(stub.path, keywords)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "FTS backfill failed for ${file.name}: ${e.message}")
                }
            }
        }
        RuLog.d(TAG) { "FTS keywords backfilled for $count files" }
    }

    fun search(
        enrichedQuery: EnrichedQuery,
        topK: Int = 10,
        semanticEnabled: Boolean = true
    ): List<SearchResult> = searchWithDiagnostics(
        enrichedQuery, topK, semanticEnabled
    ).results

    fun searchWithDiagnostics(
        enrichedQuery: EnrichedQuery,
        topK: Int = 10,
        semanticEnabled: Boolean = true,
        trackHint: String? = null
    ): SearchPipeline.SearchOutcome = synchronized(searchLock) {
        SearchPipeline.searchWithDiagnostics(
            enrichedQuery   = enrichedQuery,
            engine          = engine,
            db              = db,
            topK            = topK,
            semanticEnabled = semanticEnabled,
            trackHint       = trackHint
        )
    }

    fun clear() {
        // DB cleared by caller; counts read live from SQLite.
    }

    private fun filePriority(file: File): Int {
        val path = file.absolutePath
        for ((i, dir) in PRIORITY_DIRS.withIndex()) {
            if (path.contains(dir, ignoreCase = true)) return i
        }
        return PRIORITY_DIRS.size
    }

    private fun pathPriority(file: File): Int = filePriority(file)
}

data class SearchResult(
    val file: IndexedFile,
    val score: Float,
    val denseRank: Int = -1,
    val bm25Rank: Int = -1,
    val categoryMatched: Boolean = false
) {
    val scorePercent: Int get() = (score.coerceIn(0f, 1f) * 100).toInt()
}
