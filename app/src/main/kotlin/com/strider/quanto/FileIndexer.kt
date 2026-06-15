package com.strider.quanto

import android.util.Log
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

class FileIndexer(
    private val engine: EmbeddingEngine,
    private val db: DatabaseHelper
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

        /** Batch size tuned for 4-core mid-range ARM (Redmi-class devices). */
        private const val BATCH_SIZE = 8

        /** Brief pause between batches to reduce sustained CPU heat. */
        private const val BATCH_COOLDOWN_MS = 40L
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
        Log.d(TAG, "Index ready — $count files (disk-backed search)")
        Log.d(TAG, "Category counts: ${getBucketSizes()}")
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
        onProgress: suspend (String) -> Unit,
        onFileIndexed: suspend (Int, Int, Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        val root = File(rootPath)
        if (!root.exists() || !root.canRead()) {
            onProgress("Cannot read $rootPath — check permissions")
            return@withContext
        }

        val storedMeta = db.getStoredFileMeta()
        Log.d(TAG, "Stored in DB: ${storedMeta.size} files")

        onProgress("Scanning storage for files… (this can take a few minutes)")

        val allDiskFiles = root.walkTopDown()
            .onEnter { dir ->
                val path = dir.absolutePath
                SKIP_DIRS.none { s -> path.contains(s) }
            }
            .filter { it.isFile }
            .filter { it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .toList()

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

        Log.d(TAG, "Files on disk: ${diskFiles.size} (no cap — all will be indexed)")

        val diskPaths = diskFiles.map { it.absolutePath }.toSet()
        val deletedPaths = storedMeta.keys.filter { it !in diskPaths }
        if (deletedPaths.isNotEmpty()) {
            db.deleteFiles(deletedPaths)
            Log.d(TAG, "Deleted ${deletedPaths.size} stale records")
        }

        val toIndex = diskFiles.filter { file ->
            val stored = storedMeta[file.absolutePath]
            when {
                stored == null -> true
                stored.first != file.lastModified() -> true
                stored.second != file.length() -> true
                else -> false
            }
        }

        Log.d(TAG, "To index: ${toIndex.size}, Skipped (unchanged): ${diskFiles.size - toIndex.size}")

        var indexedCount = 0
        val skippedCount = diskFiles.size - toIndex.size

        toIndex.chunked(BATCH_SIZE).forEach { batch ->
            try {
                onProgress("Indexing batch of ${batch.size}… (${db.getTotalCount()} total so far)")

                val metadatas = batch.map { buildFileMetadata(it) }
                val pairs = batch.zip(metadatas).filter { (_, meta) ->
                    meta.metadataString.isNotBlank().also { ok ->
                        if (!ok) Log.w(TAG, "Skipping blank metadata")
                    }
                }
                if (pairs.isEmpty()) return@forEach

                val texts = pairs.map { it.second.metadataString }
                val embeddings = engine.embedBatch(texts)

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

                    db.upsertFile(indexed)
                    val keywords = buildKeywordString(
                        file, metadata.contentSnippet, metadata, metadata.pdfMetadata
                    )
                    db.ftsInsert(file.absolutePath, keywords)
                    indexedCount++
                    onFileIndexed(indexedCount, skippedCount, deletedPaths.size)
                    Log.d(TAG, "Indexed [${metadata.categories.joinToString { it.label }}] ${file.name}")
                }

                if (BATCH_COOLDOWN_MS > 0) delay(BATCH_COOLDOWN_MS)
            } catch (e: Exception) {
                Log.e(TAG, "Batch failed, falling back to single-file: ${e.message}", e)
                for (file in batch) {
                    try {
                        onProgress("Indexing: ${file.name}")
                        val metadata  = buildFileMetadata(file)
                        if (metadata.metadataString.isBlank()) continue
                        val embedding = engine.embed(metadata.metadataString)

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

                        db.upsertFile(indexed)
                        val keywords = buildKeywordString(
                            file, metadata.contentSnippet, metadata, metadata.pdfMetadata
                        )
                        db.ftsInsert(file.absolutePath, keywords)
                        indexedCount++
                        onFileIndexed(indexedCount, skippedCount, deletedPaths.size)
                    } catch (inner: Exception) {
                        Log.e(TAG, "Failed to index ${file.name}: ${inner.message}", inner)
                    }
                }
            }
        }

        loadFromDatabase()
        db.warmEmbeddingCache()
        Log.d(TAG, "Done — indexed:$indexedCount skipped:$skippedCount deleted:${deletedPaths.size} total:$size")
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
        Log.d(TAG, "FTS keywords backfilled for $count files")
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
