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
        private const val MAX_FILES = 5000

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

    private val buckets = mapOf(
        Category.IDENTITY  to mutableListOf<IndexedFileStub>(),
        Category.WORK      to mutableListOf<IndexedFileStub>(),
        Category.EDUCATION to mutableListOf<IndexedFileStub>(),
        Category.PERSONAL  to mutableListOf<IndexedFileStub>(),
        Category.MEDIA     to mutableListOf<IndexedFileStub>(),
        Category.GENERAL   to mutableListOf<IndexedFileStub>()
    )

    val size: Int get() = buckets.values.sumOf { it.size }

    fun getBucketSizes(): Map<Category, Int> = buckets.mapValues { it.value.size }

    private var deferredFtsFiles: List<File>? = null

    fun loadFromDatabase(deferFtsBackfill: Boolean = false) {
        buckets.values.forEach { it.clear() }
        val stubs = db.loadStubs()
        for (stub in stubs) {
            for (cat in stub.categories) {
                buckets[cat]?.add(stub)
            }
        }
        if (deferFtsBackfill) {
            deferredFtsFiles = stubs.map { File(it.path) }.filter { it.exists() }
        } else {
            backfillFtsKeywords(stubs.map { File(it.path) }.filter { it.exists() })
        }
        Log.d(TAG, "Loaded ${stubs.size} file stubs into memory")
        Log.d(TAG, "Bucket sizes: ${getBucketSizes()}")
    }

    /** Runs FTS backfill deferred from startup so the UI is not blocked. */
    fun runDeferredFtsBackfill() {
        val files = deferredFtsFiles ?: return
        deferredFtsFiles = null
        backfillFtsKeywords(files)
    }

    fun buildKeywordString(file: File, contentSnippet: String?, metadata: FileMetadata): String {
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
        entities.month?.let { parts.add(it) }
        entities.year?.let { parts.add(it) }
        entities.quarter?.let { parts.add(it) }
        entities.documentNumber?.let { parts.add(it) }
        entities.version?.let { parts.add(it) }

        if (file.extension.lowercase() == "pdf") {
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

        onProgress("Found ${allDiskFiles.size} supported files — preparing index…")

        val diskFiles = allDiskFiles
            .sortedWith(
                compareBy<File>(
                    { filePriority(it) },
                    { file ->
                        val idx = SUPPORTED_EXTENSIONS_ORDERED.indexOf(file.extension.lowercase())
                        if (idx == -1) Int.MAX_VALUE else idx
                    }
                )
            )
            .take(MAX_FILES)

        Log.d(TAG, "Files on disk: ${allDiskFiles.size}, taking top ${diskFiles.size} by priority")

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

        // Process in batches — one ONNX forward pass per batch (3-5× faster than sequential)
        toIndex.chunked(BATCH_SIZE).forEach { batch ->
            try {
                onProgress("Indexing batch of ${batch.size}…")

                val metadatas = batch.map { buildFileMetadata(it) }
                val texts = metadatas.map { it.metadataString }
                val embeddings = engine.embedBatch(texts)

                batch.forEachIndexed { i, file ->
                    val metadata = metadatas[i]
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
                    val keywords = buildKeywordString(file, metadata.contentSnippet, metadata)
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
                        val keywords = buildKeywordString(file, metadata.contentSnippet, metadata)
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
        backfillFtsKeywords(diskFiles)
        Log.d(TAG, "Done — indexed:$indexedCount skipped:$skippedCount deleted:${deletedPaths.size} total:$size")
    }

    /** Populate FTS for all on-disk files without re-embedding unchanged files. */
    private fun backfillFtsKeywords(diskFiles: List<File>) {
        val stubs = db.loadStubs().associateBy { it.path }
        var count = 0
        for (file in diskFiles) {
            val stub = stubs[file.absolutePath] ?: continue
            try {
                val keywords = buildKeywordString(file, stub.metadata.contentSnippet, stub.metadata)
                db.ftsInsert(file.absolutePath, keywords)
                count++
            } catch (e: Exception) {
                Log.w(TAG, "FTS backfill failed for ${file.name}: ${e.message}")
            }
        }
        Log.d(TAG, "FTS keywords backfilled for $count files")
    }

    fun search(
        enrichedQuery: EnrichedQuery,
        topK: Int = 10,
        semanticEnabled: Boolean = true
    ): List<SearchResult> = synchronized(searchLock) {
        SearchPipeline.search(
            stubs          = allStubs(),
            enrichedQuery  = enrichedQuery,
            engine         = engine,
            loadEmbeddings = { paths -> db.loadEmbeddingsForPaths(paths) },
            topK           = topK,
            semanticEnabled = semanticEnabled
        )
    }

    private fun stubToFile(stub: IndexedFileStub): IndexedFile =
        IndexedFile.fromStub(stub, FloatArray(EmbeddingEngine.EMBEDDING_DIM))

    private fun allStubs(): List<IndexedFileStub> =
        buckets.values.flatten().distinctBy { it.path }

    fun clear() {
        buckets.values.forEach { it.clear() }
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
