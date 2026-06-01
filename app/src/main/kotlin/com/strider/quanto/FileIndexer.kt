package com.strider.quanto

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
}

class FileIndexer(
    private val engine: EmbeddingEngine,
    private val db: DatabaseHelper
) {

    companion object {
        private const val TAG = "FileIndexer"
        private const val MAX_FILES = 5000

        private val SUPPORTED_EXTENSIONS_ORDERED = listOf(
            // Priority 1 — Documents (most useful for search)
            "txt", "md", "pdf", "doc", "docx",
            "xls", "xlsx", "ppt", "pptx",
            "csv", "json", "xml", "html", "htm",
            // Priority 2 — Code
            "py", "js", "ts", "kt", "java", "cpp", "c", "h",
            // Priority 3 — Images
            "jpg", "jpeg", "png", "webp", "heic", "gif",
            // Priority 4 — Audio
            "mp3", "aac", "flac", "wav", "m4a",
            // Priority 5 — Video
            "mp4", "mkv", "avi", "mov",
            // Priority 6 — Archives
            "zip", "rar", "7z", "tar", "gz", "apk"
        )

        private val SUPPORTED_EXTENSIONS = SUPPORTED_EXTENSIONS_ORDERED.toSet()

        private val SKIP_DIRS = setOf(
            "Android/data", "Android/obb", ".thumbnails",
            ".cache", "lost+found", ".trash"
        )
    }

    // ─────────────────────────────────────────────
    // In-memory categorized buckets (loaded from DB)
    // ─────────────────────────────────────────────

    private val buckets = mapOf(
        Category.IDENTITY  to mutableListOf<IndexedFile>(),
        Category.WORK      to mutableListOf<IndexedFile>(),
        Category.EDUCATION to mutableListOf<IndexedFile>(),
        Category.PERSONAL  to mutableListOf<IndexedFile>(),
        Category.MEDIA     to mutableListOf<IndexedFile>(),
        Category.GENERAL   to mutableListOf<IndexedFile>()
    )

    val size: Int get() = buckets.values.sumOf { it.size }

    fun getBucketSizes(): Map<Category, Int> = buckets.mapValues { it.value.size }

    // ─────────────────────────────────────────────
    // Load from DB into memory (fast startup)
    // ─────────────────────────────────────────────

    fun loadFromDatabase() {
        buckets.values.forEach { it.clear() }
        val files = db.loadAll()
        for (file in files) {
            for (cat in file.categories) {
                buckets[cat]?.add(file)
            }
        }
        Log.d(TAG, "Loaded ${files.size} files from DB into memory")
        Log.d(TAG, "Bucket sizes: ${getBucketSizes()}")
    }

    // ─────────────────────────────────────────────
    // Delta indexing — only process new/changed files
    // ─────────────────────────────────────────────

    suspend fun indexDirectory(
        rootPath: String,
        onProgress: suspend (String) -> Unit,
        onFileIndexed: suspend (Int, Int, Int) -> Unit  // (indexed, skipped, deleted)
    ) = withContext(Dispatchers.IO) {

        val root = File(rootPath)
        if (!root.exists() || !root.canRead()) {
            onProgress("Cannot read $rootPath — check permissions")
            return@withContext
        }

        // Step 1: get stored records for delta comparison
        val storedMeta = db.getStoredFileMeta()
        Log.d(TAG, "Stored in DB: ${storedMeta.size} files")

        // Step 2: walk filesystem
        // Walk and collect all supported files
        val allDiskFiles = root.walkTopDown()
            .onEnter { dir ->
                val path = dir.absolutePath
                SKIP_DIRS.none { s -> path.contains(s) }
            }
            .filter { it.isFile }
            .filter { it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .toList()

// Sort by priority: documents first, then code, then media
        val diskFiles = allDiskFiles
            .sortedBy { file ->
                val idx = SUPPORTED_EXTENSIONS_ORDERED.indexOf(file.extension.lowercase())
                if (idx == -1) Int.MAX_VALUE else idx
            }
            .take(MAX_FILES)

        Log.d(TAG, "Files on disk: ${allDiskFiles.size}, taking top ${diskFiles.size} by priority")

        Log.d(TAG, "Files on disk: ${diskFiles.size}")

        // Step 3: find deleted files (in DB but not on disk)
        val diskPaths = diskFiles.map { it.absolutePath }.toSet()
        val deletedPaths = storedMeta.keys.filter { it !in diskPaths }
        if (deletedPaths.isNotEmpty()) {
            db.deleteFiles(deletedPaths)
            Log.d(TAG, "Deleted ${deletedPaths.size} stale records")
        }

        // Step 4: find new and changed files
        val toIndex = diskFiles.filter { file ->
            val stored = storedMeta[file.absolutePath]
            when {
                stored == null -> true  // new file
                stored.first != file.lastModified() -> true  // modified
                stored.second != file.length() -> true  // size changed
                else -> false  // unchanged — skip
            }
        }

        Log.d(TAG, "To index: ${toIndex.size}, Skipped (unchanged): ${diskFiles.size - toIndex.size}")

        var indexedCount = 0
        val skippedCount = diskFiles.size - toIndex.size

        // Step 5: index new/changed files
        for (file in toIndex) {
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

                // Persist to SQLite
                db.upsertFile(indexed)
                indexedCount++

                onFileIndexed(indexedCount, skippedCount, deletedPaths.size)
                Log.d(TAG, "Indexed [${metadata.categories.joinToString { it.label }}] ${file.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to index ${file.name}: ${e.message}", e)
            }
        }

        // Step 6: reload all from DB into memory buckets
        loadFromDatabase()

        Log.d(TAG, "Done — indexed:$indexedCount skipped:$skippedCount deleted:${deletedPaths.size} total:$size")
    }

    // ─────────────────────────────────────────────
    // Smart search — bucket-first with fallback
    // ─────────────────────────────────────────────

    fun search(enrichedQuery: EnrichedQuery, topK: Int = 20): List<SearchResult> {
        val queryEmbedding = engine.embed(enrichedQuery.enrichedString)

        val primaryResults = if (enrichedQuery.categoryHints.isNotEmpty()) {
            val primaryFiles = enrichedQuery.categoryHints
                .flatMap { cat -> buckets[cat] ?: emptyList() }
                .distinctBy { it.path }

            Log.d(TAG, "Primary search: ${primaryFiles.size} files in ${enrichedQuery.categoryHints}")
            scoreAndRank(primaryFiles, queryEmbedding, topK)
        } else emptyList()

        return if (primaryResults.size >= 5) {
            Log.d(TAG, "Primary returned ${primaryResults.size} results")
            primaryResults
        } else {
            Log.d(TAG, "Falling back to full index search")
            val allFiles = buckets.values.flatten().distinctBy { it.path }
            scoreAndRank(allFiles, queryEmbedding, topK)
        }
    }

    private fun scoreAndRank(
        files: List<IndexedFile>,
        queryEmbedding: FloatArray,
        topK: Int
    ): List<SearchResult> {
        return files
            .map { SearchResult(it, engine.cosineSimilarity(queryEmbedding, it.embedding)) }
            .sortedByDescending { it.score }
            .take(topK)
            .filter { it.score > 0.1f }
    }

    fun clear() {
        buckets.values.forEach { it.clear() }
    }
}

data class SearchResult(
    val file: IndexedFile,
    val score: Float
) {
    val scorePercent: Int get() = (score * 100).toInt()
}