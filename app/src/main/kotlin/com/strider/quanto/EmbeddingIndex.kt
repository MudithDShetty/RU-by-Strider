package com.strider.quanto

import android.util.Log

private const val TAG = "EmbeddingIndex"
private const val DIM = EmbeddingEngine.EMBEDDING_DIM
/** Debug rank tracking only — caps work when broad hints match many paths. */
private const val MAX_TRACKED_HINT_PATHS = 8

/**
 * In-memory embedding matrix for fast full-library dense scan.
 * ~37 MB for 24k × 384-dim vectors — avoids re-reading SQLite blobs every search.
 */
class EmbeddingIndex {

    enum class State { COLD, LOADING, WARM }

    @Volatile var state: State = State.COLD
        private set

    private val lock = Any()
    private var paths: Array<String> = emptyArray()
    /** Flat row-major: index i → matrix[i * DIM .. (i+1) * DIM) */
    private var matrix: FloatArray = FloatArray(0)
    private var pathToRow: HashMap<String, Int> = hashMapOf()
    private var rowCount = 0

    val isWarm: Boolean get() = state == State.WARM && rowCount > 0

    val cachedCount: Int get() = rowCount

    fun clear() {
        synchronized(lock) {
            state = State.COLD
            rowCount = 0
            paths = emptyArray()
            matrix = FloatArray(0)
            pathToRow.clear()
        }
    }

    fun loadFrom(db: DatabaseHelper) {
        synchronized(lock) {
            state = State.LOADING
            val startMs = System.currentTimeMillis()
            val loadedPaths = ArrayList<String>(32_768)
            val loadedMatrix = ArrayList<Float>(32_768 * DIM)
            val readable = db.readableDatabase
            val cursor = readable.query(
                DatabaseHelper.TABLE,
                arrayOf(DatabaseHelper.COL_PATH, DatabaseHelper.COL_EMBEDDING),
                null, null, null, null, null
            )
            cursor.use {
                while (it.moveToNext()) {
                    val path = it.getString(0)
                    val emb = db.blobToFloatArray(it.getBlob(1))
                    if (emb.size != DIM) continue
                    loadedPaths.add(path)
                    for (v in emb) loadedMatrix.add(v)
                }
            }
            rowCount = loadedPaths.size
            paths = loadedPaths.toTypedArray()
            matrix = loadedMatrix.toFloatArray()
            pathToRow = HashMap<String, Int>(rowCount * 2).also { map ->
                paths.forEachIndexed { i, p -> map[p] = i }
            }
            state = if (rowCount > 0) State.WARM else State.COLD
            Log.i(TAG, "Warm index: $rowCount vectors in ${System.currentTimeMillis() - startMs}ms")
        }
    }

    fun upsert(path: String, embedding: FloatArray) {
        if (embedding.size != DIM) return
        synchronized(lock) {
            if (state != State.WARM) return
            val row = pathToRow[path]
            if (row != null) {
                val off = row * DIM
                for (i in 0 until DIM) matrix[off + i] = embedding[i]
                return
            }
            val newRow = rowCount
            paths = Array(rowCount + 1) { i ->
                if (i < rowCount) paths[i] else path
            }
            val newMatrix = FloatArray((rowCount + 1) * DIM)
            if (rowCount > 0) matrix.copyInto(newMatrix, 0, 0, rowCount * DIM)
            for (i in 0 until DIM) newMatrix[newRow * DIM + i] = embedding[i]
            matrix = newMatrix
            pathToRow[path] = newRow
            rowCount++
        }
    }

    fun getEmbeddings(paths: Collection<String>): Map<String, FloatArray> {
        if (paths.isEmpty()) return emptyMap()
        synchronized(lock) {
            if (state != State.WARM || rowCount == 0) return emptyMap()
            val result = HashMap<String, FloatArray>(paths.size)
            for (path in paths) {
                val row = pathToRow[path] ?: continue
                val off = row * DIM
                result[path] = FloatArray(DIM) { j -> matrix[off + j] }
            }
            return result
        }
    }

    fun remove(pathsToRemove: Collection<String>) {
        if (pathsToRemove.isEmpty()) return
        synchronized(lock) {
            if (state != State.WARM) return
            val removeSet = pathsToRemove.toSet()
            if (removeSet.size >= rowCount / 3) {
                state = State.COLD
                rowCount = 0
                paths = emptyArray()
                matrix = FloatArray(0)
                pathToRow.clear()
                return
            }
            val keptPaths = ArrayList<String>(rowCount)
            val keptMatrix = ArrayList<Float>(rowCount * DIM)
            for (i in 0 until rowCount) {
                val p = paths[i]
                if (p in removeSet) continue
                keptPaths.add(p)
                val off = i * DIM
                for (j in 0 until DIM) keptMatrix.add(matrix[off + j])
            }
            rowCount = keptPaths.size
            paths = keptPaths.toTypedArray()
            matrix = keptMatrix.toFloatArray()
            pathToRow = HashMap<String, Int>(rowCount * 2).also { map ->
                paths.forEachIndexed { idx, p -> map[p] = idx }
            }
        }
    }

    fun renamePath(oldPath: String, newPath: String) {
        synchronized(lock) {
            if (state != State.WARM) return
            val row = pathToRow.remove(oldPath) ?: return
            paths[row] = newPath
            pathToRow[newPath] = row
        }
    }

    fun scan(
        queryEmb: FloatArray,
        topK: Int,
        trackNameHints: List<String> = emptyList()
    ): DenseScanResult {
        if (queryEmb.size != DIM) return DenseScanResult(emptyList(), emptyList(), 0)

        synchronized(lock) {
            if (state != State.WARM || rowCount == 0) {
                return DenseScanResult(emptyList(), emptyList(), 0)
            }

            val hints = trackNameHints.map { it.lowercase().trim() }.filter { it.isNotEmpty() }
            val top = ArrayList<Pair<String, Float>>(topK)
            val trackedScores = linkedMapOf<String, Float>()
            val higherCounts = linkedMapOf<String, Int>()

            fun pathMatchesHint(path: String): Boolean {
                if (hints.isEmpty()) return false
                val lowerPath = path.lowercase()
                val name = path.substringAfterLast('/').lowercase()
                return hints.any { h -> h in lowerPath || h in name || name.contains(h) }
            }

            for (i in 0 until rowCount) {
                val path = paths[i]
                val off = i * DIM
                var dot = 0f
                for (j in 0 until DIM) dot += queryEmb[j] * matrix[off + j]
                dot = dot.coerceIn(-1f, 1f)

                if (pathMatchesHint(path) &&
                    trackedScores.size < MAX_TRACKED_HINT_PATHS &&
                    path !in trackedScores
                ) {
                    trackedScores[path] = dot
                    higherCounts.putIfAbsent(path, 0)
                }
                for ((tp, ts) in trackedScores) {
                    if (dot > ts) higherCounts[tp] = (higherCounts[tp] ?: 0) + 1
                }

                insertTopK(top, path, dot, topK)
            }

            val tracked = trackedScores.map { (path, score) ->
                DenseScanResult.TrackedRank(
                    path = path,
                    rank = (higherCounts[path] ?: 0) + 1,
                    score = score,
                    inTopK = top.any { it.first == path }
                )
            }.sortedBy { it.rank }

            val topOut = if (top.size == topK) top else top.sortedByDescending { it.second }
            return DenseScanResult(topOut, tracked, rowCount)
        }
    }

    private fun insertTopK(
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
}
