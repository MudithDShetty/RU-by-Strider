package com.strider.quanto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "DatabaseHelper"
private const val DB_NAME = "ru_index.db"
private const val DB_VERSION = 1

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

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
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID             INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PATH           TEXT UNIQUE NOT NULL,
                $COL_NAME           TEXT NOT NULL,
                $COL_EXT            TEXT NOT NULL,
                $COL_SIZE           INTEGER NOT NULL,
                $COL_LAST_MODIFIED  INTEGER NOT NULL,
                $COL_CATEGORIES     TEXT NOT NULL,
                $COL_AGE_BUCKET     TEXT NOT NULL,
                $COL_SIZE_BUCKET    TEXT NOT NULL,
                $COL_TYPE_LABEL     TEXT NOT NULL,
                $COL_CONTENT_SNIP   TEXT,
                $COL_METADATA_STR   TEXT NOT NULL,
                $COL_EMBEDDING      BLOB NOT NULL,
                $COL_INDEXED_AT     INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("CREATE INDEX idx_path ON $TABLE ($COL_PATH)")
        db.execSQL("CREATE INDEX idx_ext  ON $TABLE ($COL_EXT)")
        db.execSQL("CREATE INDEX idx_cats ON $TABLE ($COL_CATEGORIES)")

        Log.d(TAG, "Database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ─────────────────────────────────────────────
    // Embedding serialization
    // ─────────────────────────────────────────────

    fun floatArrayToBlob(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4)
        arr.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun blobToFloatArray(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob)
        return FloatArray(blob.size / 4) { buf.getFloat() }
    }

    // ─────────────────────────────────────────────
    // Insert or replace
    // ─────────────────────────────────────────────

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
        }
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ─────────────────────────────────────────────
    // Load all files from DB into memory
    // ─────────────────────────────────────────────

    fun loadAll(): List<IndexedFile> {
        val db = readableDatabase
        val result = mutableListOf<IndexedFile>()

        val cursor = db.query(TABLE, null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                try {
                    val cats = it.getString(it.getColumnIndexOrThrow(COL_CATEGORIES))
                        .split(",")
                        .mapNotNull { name ->
                            try { Category.valueOf(name) } catch (e: Exception) { null }
                        }

                    val metadata = FileMetadata(
                        metadataString = it.getString(it.getColumnIndexOrThrow(COL_METADATA_STR)),
                        categories     = cats,
                        contentSnippet = it.getString(it.getColumnIndexOrThrow(COL_CONTENT_SNIP)),
                        ageBucket      = it.getString(it.getColumnIndexOrThrow(COL_AGE_BUCKET)),
                        sizeBucket     = it.getString(it.getColumnIndexOrThrow(COL_SIZE_BUCKET)),
                        typeLabel      = it.getString(it.getColumnIndexOrThrow(COL_TYPE_LABEL))
                    )

                    Log.d(TAG, "Loaded: ${it.getString(it.getColumnIndexOrThrow(COL_PATH))} | Metadata: ${metadata.metadataString}")

                    result.add(IndexedFile(
                        path         = it.getString(it.getColumnIndexOrThrow(COL_PATH)),
                        name         = it.getString(it.getColumnIndexOrThrow(COL_NAME)),
                        extension    = it.getString(it.getColumnIndexOrThrow(COL_EXT)),
                        sizeBytes    = it.getLong(it.getColumnIndexOrThrow(COL_SIZE)),
                        lastModified = it.getLong(it.getColumnIndexOrThrow(COL_LAST_MODIFIED)),
                        embedding    = blobToFloatArray(
                            it.getBlob(it.getColumnIndexOrThrow(COL_EMBEDDING))
                        ),
                        categories   = cats,
                        metadata     = metadata
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load row: ${e.message}")
                }
            }
        }

        Log.d(TAG, "Loaded ${result.size} files from DB")
        return result
    }

    // ─────────────────────────────────────────────
    // Get stored file records for delta check
    // ─────────────────────────────────────────────

    fun getStoredFileMeta(): Map<String, Pair<Long, Long>> {
        // path → (lastModified, sizeBytes)
        val db = readableDatabase
        val result = mutableMapOf<String, Pair<Long, Long>>()
        val cursor = db.query(
            TABLE,
            arrayOf(COL_PATH, COL_LAST_MODIFIED, COL_SIZE),
            null, null, null, null, null
        )
        cursor.use {
            while (it.moveToNext()) {
                val path = it.getString(0)
                val lm   = it.getLong(1)
                val size = it.getLong(2)
                result[path] = Pair(lm, size)
            }
        }
        return result
    }

    // ─────────────────────────────────────────────
    // Delete removed files
    // ─────────────────────────────────────────────

    fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val db = writableDatabase
        val placeholders = paths.joinToString(",") { "?" }
        val deleted = db.delete(TABLE, "$COL_PATH IN ($placeholders)", paths.toTypedArray())
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
}