package com.strider.quanto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "DatabaseHelper"
private const val DB_NAME = "ru_index.db"
private const val DB_VERSION = 6
private const val FTS_TABLE = "fts_index"

private enum class FtsMode { FTS5, FTS4, NONE }

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private var ftsMode = FtsMode.NONE
    private val embeddingIndex = EmbeddingIndex()

    companion object {
        const val TABLE = "indexed_files"
        const val COL_ID            = "id"
        const val COL_PATH          = "path"
        const val COL_NAME          = "name"
        const val COL_EXT           = "extension"
        const val COL_SIZE          = "size_bytes"
        const val COL_LAST_MODIFIED = "last_modified"
        const val COL_CATEGORIES    = "categories"
        const val COL_AGE_BUCKET    = "age_bucket"
        const val COL_SIZE_BUCKET   = "size_bucket"
        const val COL_TYPE_LABEL    = "type_label"
        const val COL_CONTENT_SNIP  = "content_snip"
        const val COL_METADATA_STR  = "metadata_str"
        const val COL_EMBEDDING     = "embedding"
        const val COL_INDEXED_AT    = "indexed_at"
        const val COL_OWNER_NAMES   = "owner_names"
        const val COL_OWNER_CONF    = "owner_confidence"
        const val COL_ENTITIES      = "entities"
        const val COL_OCR_PENDING   = "ocr_pending"
        /** Reserved for future hierarchy embeddings; unused in app — keeps DB v6 compatible. */
        const val COL_HYP_EMBEDDING = "hyp_embedding"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
            "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "$COL_PATH TEXT UNIQUE NOT NULL, " +
            "$COL_NAME TEXT NOT NULL, " +
            "$COL_EXT TEXT NOT NULL, " +
            "$COL_SIZE INTEGER NOT NULL, " +
            "$COL_LAST_MODIFIED INTEGER NOT NULL, " +
            "$COL_CATEGORIES TEXT NOT NULL, " +
            "$COL_AGE_BUCKET TEXT NOT NULL, " +
            "$COL_SIZE_BUCKET TEXT NOT NULL, " +
            "$COL_TYPE_LABEL TEXT NOT NULL, " +
            "$COL_CONTENT_SNIP TEXT, " +
            "$COL_METADATA_STR TEXT NOT NULL, " +
            "$COL_EMBEDDING BLOB NOT NULL, " +
            "$COL_INDEXED_AT INTEGER NOT NULL, " +
            "$COL_OWNER_NAMES TEXT, " +
            "$COL_OWNER_CONF REAL NOT NULL DEFAULT 0, " +
            "$COL_ENTITIES TEXT, " +
            "$COL_OCR_PENDING INTEGER NOT NULL DEFAULT 0, " +
            "$COL_HYP_EMBEDDING BLOB)"
        )

        db.execSQL("CREATE INDEX idx_path ON $TABLE ($COL_PATH)")
        db.execSQL("CREATE INDEX idx_ext  ON $TABLE ($COL_EXT)")
        db.execSQL("CREATE INDEX idx_cats ON $TABLE ($COL_CATEGORIES)")

        createFtsTable(db)
        Log.d(TAG, "Database created (FTS mode: $ftsMode)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createFtsTable(db)
            db.execSQL("UPDATE $TABLE SET $COL_LAST_MODIFIED = 0")
            Log.d(TAG, "DB upgraded to v2: FTS added ($ftsMode), all files queued for re-index")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_OWNER_NAMES TEXT")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_OWNER_CONF REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE $TABLE SET $COL_LAST_MODIFIED = 0")
            Log.d(TAG, "DB upgraded to v3: owner_names added, all files queued for re-index")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_ENTITIES TEXT")
            db.execSQL("UPDATE $TABLE SET $COL_LAST_MODIFIED = 0")
            Log.d(TAG, "DB upgraded to v4: entities column added, all files queued for re-index")
        }
        if (oldVersion < 5) {
            db.execSQL(
                "ALTER TABLE $TABLE ADD COLUMN $COL_OCR_PENDING INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "UPDATE $TABLE SET $COL_OCR_PENDING = 1 " +
                    "WHERE $COL_EXT = 'pdf' AND (" +
                    "$COL_CONTENT_SNIP IS NULL OR LENGTH($COL_CONTENT_SNIP) < 30)"
            )
            Log.d(TAG, "DB upgraded to v5: ocr_pending added, weak PDFs queued for OCR")
        }
        if (oldVersion < 6) {
            addHypEmbeddingColumnIfMissing(db)
            Log.d(TAG, "DB upgraded to v6: hyp_embedding column (reserved, unused)")
        }
    }

    private fun addHypEmbeddingColumnIfMissing(db: SQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_HYP_EMBEDDING BLOB")
        } catch (e: Exception) {
            // Column may already exist from experimental build — safe to ignore.
            Log.w(TAG, "hyp_embedding column add skipped: ${e.message}")
        }
    }

    private fun createFtsTable(db: SQLiteDatabase) {
        try {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_TABLE USING fts5(" +
                "path UNINDEXED, keywords, tokenize='unicode61 remove_diacritics 1')"
            )
            ftsMode = FtsMode.FTS5
            return
        } catch (e: Exception) {
            Log.w(TAG, "FTS5 unavailable: ${e.message}")
        }

        try {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_TABLE USING fts4(" +
                "path, keywords, notindexed=path, tokenize=unicode61)"
            )
            ftsMode = FtsMode.FTS4
            Log.d(TAG, "Using FTS4 fallback for keyword search")
        } catch (e: Exception) {
            ftsMode = FtsMode.NONE
            Log.w(TAG, "FTS unavailable — keyword search disabled: ${e.message}")
        }
    }

    fun floatArrayToBlob(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun blobToFloatArray(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob)
        return FloatArray(blob.size / 4) { buf.getFloat() }
    }

    fun upsertFile(file: IndexedFile, ocrPending: Boolean = false) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Upserting: ${file.path} | Metadata: ${file.metadata.metadataString}")
        }
        val db = writableDatabase
        val cv = ContentValues().apply {
            put(COL_PATH,          file.path)
            put(COL_NAME,          file.name)
            put(COL_EXT,           file.extension)
            put(COL_SIZE,          file.sizeBytes)
            put(COL_LAST_MODIFIED, file.lastModified)
            put(COL_CATEGORIES,    file.categories.joinToString(",") { it.name })
            put(COL_AGE_BUCKET,    file.metadata.ageBucket)
            put(COL_SIZE_BUCKET,   file.metadata.sizeBucket)
            put(COL_TYPE_LABEL,    file.metadata.typeLabel)
            put(COL_CONTENT_SNIP,  file.metadata.contentSnippet)
            put(COL_METADATA_STR,  file.metadata.metadataString)
            put(COL_EMBEDDING,     floatArrayToBlob(file.embedding))
            put(COL_INDEXED_AT,    System.currentTimeMillis())
            put(COL_OWNER_NAMES,   OwnerMatcher.serializeEntities(file.metadata.ownerEntities))
            put(COL_OWNER_CONF,    file.metadata.ownerConfidence)
            put(COL_ENTITIES,      serializeEntities(file.metadata.extractedEntities))
            put(COL_OCR_PENDING,   if (ocrPending) 1 else 0)
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        embeddingIndex.upsert(file.path, file.embedding)
    }

    fun getOcrPendingCount(): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $COL_EXT = 'pdf' AND $COL_OCR_PENDING = 1",
            null
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getOcrPendingPaths(limit: Int): List<String> {
        val cursor = readableDatabase.query(
            TABLE,
            arrayOf(COL_PATH),
            "$COL_EXT = 'pdf' AND $COL_OCR_PENDING = 1",
            null,
            null,
            null,
            COL_PATH,
            limit.toString()
        )
        val paths = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                paths.add(it.getString(0))
            }
        }
        return paths
    }

    fun clearOcrPending(path: String) {
        val cv = ContentValues().apply { put(COL_OCR_PENDING, 0) }
        writableDatabase.update(TABLE, cv, "$COL_PATH = ?", arrayOf(path))
    }

    /** Load all embeddings into RAM for fast dense scan (~1.5 KB per file). */
    fun warmEmbeddingCache() {
        val dbCount = getTotalCount()
        if (embeddingIndex.isWarm && embeddingIndex.cachedCount == dbCount) {
            Log.d(TAG, "Embedding cache already warm ($dbCount vectors)")
            return
        }
        embeddingIndex.loadFrom(this)
    }

    fun isEmbeddingCacheWarm(): Boolean = embeddingIndex.isWarm

    fun invalidateEmbeddingCache() {
        embeddingIndex.clear()
    }

    fun ftsInsert(path: String, keywords: String) {
        if (ftsMode == FtsMode.NONE) return
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("path", path)
            put("keywords", keywords)
        }
        db.insertWithOnConflict(FTS_TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun ftsSearch(cleanQuery: String, expandedQuery: String, limit: Int = 30): List<Pair<String, Float>> {
        if (ftsMode == FtsMode.NONE) return emptyList()

        val cleanTokens = tokenizeForFts(cleanQuery)
        if (cleanTokens.isEmpty()) return emptyList()

        // path → best score (merged across tiers)
        val scores = linkedMapOf<String, Float>()

        fun absorb(paths: List<String>, weight: Float) {
            paths.forEachIndexed { i, path ->
                val s = weight / (1f + i)
                scores[path] = maxOf(scores[path] ?: 0f, s)
            }
        }

        // Tier 1: AND on clean tokens only (precise multi-word queries)
        if (cleanTokens.size > 1) {
            absorb(runFtsQuery(cleanTokens.joinToString(" "), limit), weight = 1.0f)
        }

        // Tier 2: each clean token individually (critical for single-word queries)
        for (token in cleanTokens) {
            absorb(runFtsQuery(token, limit), weight = 0.95f)
        }

        // Tier 3: OR on synonym tokens (never AND — that was causing zero BM25 hits)
        val synonymTokens = tokenizeForFts(expandedQuery)
            .filter { it !in cleanTokens.toSet() }
            .distinct()
            .take(8)
        if (synonymTokens.isNotEmpty()) {
            absorb(runFtsQuery(synonymTokens.joinToString(" OR "), limit), weight = 0.65f)
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    private fun tokenizeForFts(query: String): List<String> =
        query.replace("\"", "\"\"")
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.length > 1 }

    private fun runFtsQuery(matchExpr: String, limit: Int): List<String> {
        if (matchExpr.isBlank()) return emptyList()
        val db = readableDatabase
        val paths = mutableListOf<String>()
        try {
            when (ftsMode) {
                FtsMode.FTS5 -> {
                    val cursor = db.rawQuery(
                        "SELECT path, rank FROM $FTS_TABLE WHERE keywords MATCH ? ORDER BY rank LIMIT ?",
                        arrayOf(matchExpr, "$limit")
                    )
                    cursor.use { while (it.moveToNext()) paths.add(it.getString(0)) }
                }
                FtsMode.FTS4 -> {
                    val cursor = db.rawQuery(
                        "SELECT path FROM $FTS_TABLE WHERE keywords MATCH ? LIMIT ?",
                        arrayOf(matchExpr, "$limit")
                    )
                    cursor.use { while (it.moveToNext()) paths.add(it.getString(0)) }
                }
                FtsMode.NONE -> return emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS query failed for '$matchExpr': ${e.message}")
        }
        return paths
    }

    /** @deprecated Use ftsSearch(clean, expanded) */
    fun ftsSearch(query: String, limit: Int = 30): List<Pair<String, Float>> =
        ftsSearch(query, query, limit)

    fun ftsDeletePaths(paths: List<String>) {
        if (paths.isEmpty() || ftsMode == FtsMode.NONE) return
        val db = writableDatabase
        val placeholders = paths.joinToString(",") { "?" }
        db.delete(FTS_TABLE, "path IN ($placeholders)", paths.toTypedArray())
    }

    fun ftsClear() {
        if (ftsMode == FtsMode.NONE) return
        writableDatabase.execSQL("DELETE FROM $FTS_TABLE")
    }

    fun loadAllPaths(): List<String> {
        val db = readableDatabase
        val paths = mutableListOf<String>()
        val cursor = db.query(TABLE, arrayOf(COL_PATH), null, null, null, null, null)
        cursor.use { while (it.moveToNext()) paths.add(it.getString(0)) }
        return paths
    }

    fun loadRecentPaths(limit: Int): List<String> {
        val db = readableDatabase
        val paths = mutableListOf<String>()
        val cursor = db.query(
            TABLE,
            arrayOf(COL_PATH),
            null, null, null, null,
            "$COL_LAST_MODIFIED DESC",
            limit.toString()
        )
        cursor.use { while (it.moveToNext()) paths.add(it.getString(0)) }
        return paths
    }

    /**
     * Full-library filename search — every token must appear in [COL_NAME] (case-insensitive).
     * Not capped by FTS/recency pools; fixes misses like "NDA- Quantoo .pdf" at 24k+ files.
     */
    fun searchPathsByNameTokens(tokens: List<String>, limit: Int = 80): List<String> {
        val usable = tokens.map { it.lowercase().trim() }.filter { it.length >= 2 }.distinct()
        if (usable.isEmpty()) return emptyList()

        val db = readableDatabase
        val paths = mutableListOf<String>()
        val nameClauses = usable.map { "LOWER($COL_NAME) LIKE ?" }
        val nameArgs = usable.map { "%$it%" }.toMutableList()

        fun runQuery(where: String, args: Array<String>): Int {
            var added = 0
            val cursor = db.query(
                TABLE,
                arrayOf(COL_PATH),
                where,
                args,
                null, null,
                "$COL_LAST_MODIFIED DESC",
                limit.toString()
            )
            cursor.use {
                while (it.moveToNext() && paths.size < limit) {
                    val path = it.getString(0)
                    if (path !in paths) {
                        paths.add(path)
                        added++
                    }
                }
            }
            return added
        }

        runQuery(nameClauses.joinToString(" AND "), nameArgs.toTypedArray())

        if (paths.size < limit) {
            val pathClauses = usable.map { "LOWER($COL_PATH) LIKE ?" }
            val pathArgs = usable.map { "%$it%" }.toTypedArray()
            runQuery(pathClauses.joinToString(" AND "), pathArgs)
        }

        Log.d(TAG, "Filename token search (${usable.joinToString()}): ${paths.size} hits")
        return paths
    }

    /**
     * Full-library content/entity search — every token must appear in snippet, metadata, or entities.
     * Bypasses FTS/recency pools for person-name and content-keyword queries at 24k+ files.
     */
    fun searchPathsByContentTokens(tokens: List<String>, limit: Int = 80): List<String> {
        val usable = tokens.map { it.lowercase().trim() }.filter { it.length >= 2 }.distinct()
        if (usable.isEmpty()) return emptyList()

        val db = readableDatabase
        val paths = mutableListOf<String>()
        val clauses = usable.map { token ->
            "(LOWER($COL_CONTENT_SNIP) LIKE ? OR LOWER($COL_METADATA_STR) LIKE ? OR LOWER($COL_ENTITIES) LIKE ?)"
        }
        val args = usable.flatMap { token ->
            val pattern = "%$token%"
            listOf(pattern, pattern, pattern)
        }.toTypedArray()

        val cursor = db.query(
            TABLE,
            arrayOf(COL_PATH),
            clauses.joinToString(" AND "),
            args,
            null, null,
            "$COL_LAST_MODIFIED DESC",
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext() && paths.size < limit) {
                paths.add(it.getString(0))
            }
        }

        Log.d(TAG, "Content token search (${usable.joinToString()}): ${paths.size} hits")
        return paths
    }

    fun serializeEntities(entities: List<String>): String? {
        val normalized = entities.map { it.lowercase().trim() }.filter { it.length >= 2 }.distinct()
        return normalized.joinToString(" ").ifBlank { null }
    }

    fun deserializeEntities(raw: String?): List<String> =
        raw?.split(Regex("\\s+"))?.filter { it.length >= 2 } ?: emptyList()

    fun loadPathsByCategories(categories: List<Category>, limit: Int): List<String> {
        if (categories.isEmpty()) return emptyList()
        val db = readableDatabase
        val clauses = categories.map { "$COL_CATEGORIES LIKE ?" }
        val args = categories.map { "%${it.name}%" }.toTypedArray()
        val paths = mutableListOf<String>()
        val cursor = db.query(
            TABLE,
            arrayOf(COL_PATH),
            clauses.joinToString(" OR "),
            args,
            null, null,
            "$COL_LAST_MODIFIED DESC",
            limit.toString()
        )
        cursor.use { while (it.moveToNext()) paths.add(it.getString(0)) }
        return paths
    }

    fun getCategoryCounts(): Map<Category, Int> {
        val db = readableDatabase
        return Category.values().mapNotNull { cat ->
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE $COL_CATEGORIES LIKE ?",
                arrayOf("%${cat.name}%")
            )
            val count = cursor.use {
                it.moveToFirst()
                it.getInt(0)
            }
            if (count > 0) cat to count else null
        }.toMap()
    }

    fun loadStubsForPaths(paths: List<String>): List<IndexedFileStub> {
        if (paths.isEmpty()) return emptyList()
        val db = readableDatabase
        val result = mutableListOf<IndexedFileStub>()
        for (chunk in paths.distinct().chunked(900)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = db.query(
                TABLE,
                arrayOf(
                    COL_PATH, COL_NAME, COL_EXT, COL_SIZE, COL_LAST_MODIFIED,
                    COL_CATEGORIES, COL_AGE_BUCKET, COL_SIZE_BUCKET,
                    COL_TYPE_LABEL, COL_CONTENT_SNIP, COL_METADATA_STR,
                    COL_OWNER_NAMES, COL_OWNER_CONF, COL_ENTITIES
                ),
                "$COL_PATH IN ($placeholders)",
                chunk.toTypedArray(),
                null, null, null
            )
            cursor.use {
                while (it.moveToNext()) {
                    parseStubRow(it)?.let { stub -> result.add(stub) }
                }
            }
        }
        Log.d(TAG, "Loaded ${result.size} stubs for ${paths.size} paths")
        return result
    }

    private fun parseStubRow(it: android.database.Cursor): IndexedFileStub? {
        return try {
            val cats = it.getString(5)
                .split(",")
                .mapNotNull { name ->
                    try { Category.valueOf(name) } catch (_: Exception) { null }
                }

            val ownerEntities = OwnerMatcher.deserializeEntities(
                if (it.columnCount > 11) it.getString(11) else null
            )
            val ownerConfidence = if (it.columnCount > 12) it.getFloat(12) else 0f
            val extractedEntities = if (it.columnCount > 13) {
                deserializeEntities(it.getString(13))
            } else {
                emptyList()
            }

            val metadata = FileMetadata(
                metadataString    = it.getString(10),
                categories        = cats,
                contentSnippet    = it.getString(9),
                ageBucket         = it.getString(6),
                sizeBucket        = it.getString(7),
                typeLabel         = it.getString(8),
                ownerEntities     = ownerEntities,
                ownerConfidence   = ownerConfidence,
                extractedEntities = extractedEntities
            )

            IndexedFileStub(
                path         = it.getString(0),
                name         = it.getString(1),
                extension    = it.getString(2),
                sizeBytes    = it.getLong(3),
                lastModified = it.getLong(4),
                categories   = cats,
                metadata     = metadata
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stub row: ${e.message}")
            null
        }
    }

    fun scanAllEmbeddingsTopK(queryEmb: FloatArray, topK: Int = 50): List<Pair<String, Float>> =
        scanAllEmbeddings(queryEmb, topK).top

    /**
     * Full-library dense scan with optional rank tracking for debug/diagnostics.
     * [trackNameHints] are matched case-insensitively against path and filename.
     */
    fun scanAllEmbeddings(
        queryEmb: FloatArray,
        topK: Int = 50,
        trackNameHints: List<String> = emptyList()
    ): DenseScanResult {
        if (queryEmb.size != EmbeddingEngine.EMBEDDING_DIM) {
            return DenseScanResult(emptyList(), emptyList(), 0)
        }
        if (embeddingIndex.isWarm) {
            val cached = embeddingIndex.scan(queryEmb, topK, trackNameHints)
            if (cached.totalScanned > 0) {
                Log.d(TAG, "Dense scan (RAM cache): ${cached.totalScanned} files → top $topK")
                return cached
            }
        }
        return scanAllEmbeddingsFromDisk(queryEmb, topK, trackNameHints)
    }

    private fun scanAllEmbeddingsFromDisk(
        queryEmb: FloatArray,
        topK: Int,
        trackNameHints: List<String>
    ): DenseScanResult {
        val hints = trackNameHints.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        val db = readableDatabase
        val top = ArrayList<Pair<String, Float>>(topK)
        val trackedScores = linkedMapOf<String, Float>()
        val higherCounts = linkedMapOf<String, Int>()

        fun pathMatchesHint(path: String): Boolean {
            if (hints.isEmpty()) return false
            val lowerPath = path.lowercase()
            val name = path.substringAfterLast('/').lowercase()
            return hints.any { h -> h in lowerPath || h in name || name.contains(h) }
        }

        var scanned = 0
        val cursor = db.query(
            TABLE,
            arrayOf(COL_PATH, COL_EMBEDDING),
            null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                scanned++
                val path = it.getString(0)
                val emb = blobToFloatArray(it.getBlob(1))
                var dot = 0f
                for (i in queryEmb.indices) dot += queryEmb[i] * emb[i]
                dot = dot.coerceIn(-1f, 1f)

                if (pathMatchesHint(path)) {
                    trackedScores[path] = dot
                    higherCounts.putIfAbsent(path, 0)
                }
                for ((tp, ts) in trackedScores) {
                    if (dot > ts) higherCounts[tp] = (higherCounts[tp] ?: 0) + 1
                }

                insertTopKByScore(top, path, dot, topK)
            }
        }

        val tracked = trackedScores.map { (path, score) ->
            val rank = (higherCounts[path] ?: 0) + 1
            DenseScanResult.TrackedRank(
                path = path,
                rank = rank,
                score = score,
                inTopK = top.any { it.first == path }
            )
        }.sortedBy { it.rank }

        Log.d(TAG, "Full-library dense scan: $scanned files → top $topK, tracked ${tracked.size}")
        return DenseScanResult(top.sortedByDescending { it.second }, tracked, scanned)
    }

    private fun insertTopKByScore(
        top: MutableList<Pair<String, Float>>,
        path: String,
        score: Float,
        k: Int
    ) {
        when {
            top.size < k -> {
                top.add(path to score)
                if (top.size == k) top.sortByDescending { it.second }
            }
            score > top.last().second -> {
                top[k - 1] = path to score
                top.sortByDescending { it.second }
            }
        }
    }

    fun loadEmbeddingsForPaths(paths: List<String>): Map<String, FloatArray> {
        if (paths.isEmpty()) return emptyMap()
        val db = readableDatabase
        val result = mutableMapOf<String, FloatArray>()
        // SQLite limits bind args (~999); chunk large path lists
        for (chunk in paths.distinct().chunked(900)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = db.query(
                TABLE,
                arrayOf(COL_PATH, COL_EMBEDDING),
                "$COL_PATH IN ($placeholders)",
                chunk.toTypedArray(),
                null, null, null
            )
            cursor.use {
                while (it.moveToNext()) {
                    result[it.getString(0)] = blobToFloatArray(it.getBlob(1))
                }
            }
        }
        return result
    }

    fun getStoredFileMeta(): Map<String, Pair<Long, Long>> {
        val db = readableDatabase
        val result = mutableMapOf<String, Pair<Long, Long>>()
        val cursor = db.query(
            TABLE,
            arrayOf(COL_PATH, COL_LAST_MODIFIED, COL_SIZE),
            null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                result[it.getString(0)] = Pair(it.getLong(1), it.getLong(2))
            }
        }
        return result
    }

    fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val db = writableDatabase
        val placeholders = paths.joinToString(",") { "?" }
        val deleted = db.delete(TABLE, "$COL_PATH IN ($placeholders)", paths.toTypedArray())
        ftsDeletePaths(paths)
        embeddingIndex.remove(paths)
        Log.d(TAG, "Deleted $deleted stale records from DB")
    }

    fun getTotalCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null)
        cursor.use {
            it.moveToFirst()
            return it.getInt(0)
        }
    }

    /** Force all indexed files to be re-processed on next index run (e.g. after owner extraction upgrade). */
    fun invalidateAllForReindex() {
        writableDatabase.execSQL("UPDATE $TABLE SET $COL_LAST_MODIFIED = 0")
        Log.d(TAG, "All files marked for re-index")
    }
}
