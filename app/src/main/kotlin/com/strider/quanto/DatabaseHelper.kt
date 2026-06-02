package com.strider.quanto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "DatabaseHelper"
private const val DB_NAME = "ru_index.db"
private const val DB_VERSION = 3
private const val FTS_TABLE = "fts_index"

private enum class FtsMode { FTS5, FTS4, NONE }

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private var ftsMode = FtsMode.NONE

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
            "$COL_OWNER_CONF REAL NOT NULL DEFAULT 0)"
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

    fun upsertFile(file: IndexedFile) {
        Log.d(TAG, "Upserting: ${file.path} | Metadata: ${file.metadata.metadataString}")
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
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
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

    fun loadStubs(): List<IndexedFileStub> {
        val db = readableDatabase
        val result = mutableListOf<IndexedFileStub>()

        val cursor = db.query(
            TABLE,
            arrayOf(
                COL_PATH, COL_NAME, COL_EXT, COL_SIZE, COL_LAST_MODIFIED,
                COL_CATEGORIES, COL_AGE_BUCKET, COL_SIZE_BUCKET,
                COL_TYPE_LABEL, COL_CONTENT_SNIP, COL_METADATA_STR,
                COL_OWNER_NAMES, COL_OWNER_CONF
            ),
            null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                try {
                    val cats = it.getString(5)
                        .split(",")
                        .mapNotNull { name ->
                            try { Category.valueOf(name) } catch (_: Exception) { null }
                        }

                    val ownerEntities = OwnerMatcher.deserializeEntities(
                        if (it.columnCount > 11) it.getString(11) else null
                    )
                    val ownerConfidence = if (it.columnCount > 12) it.getFloat(12) else 0f

                    val metadata = FileMetadata(
                        metadataString = it.getString(10),
                        categories     = cats,
                        contentSnippet = it.getString(9),
                        ageBucket      = it.getString(6),
                        sizeBucket     = it.getString(7),
                        typeLabel      = it.getString(8),
                        ownerEntities  = ownerEntities,
                        ownerConfidence = ownerConfidence
                    )

                    result.add(IndexedFileStub(
                        path         = it.getString(0),
                        name         = it.getString(1),
                        extension    = it.getString(2),
                        sizeBytes    = it.getLong(3),
                        lastModified = it.getLong(4),
                        categories   = cats,
                        metadata     = metadata
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load stub row: ${e.message}")
                }
            }
        }

        Log.d(TAG, "Loaded ${result.size} file stubs from DB")
        return result
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
