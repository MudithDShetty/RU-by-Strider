package com.strider.quanto.eval

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory live event feed for the analytics dashboard. Zero cost when eval logging is off. */
object EvalLiveFeed {

    data class LiveEvent(
        val id: String,
        val ts: String,
        val category: String,
        val eventType: String,
        val summary: String,
        val detail: String
    )

    data class DashboardState(
        val events: List<LiveEvent> = emptyList(),
        val lastSearchSummary: String? = null,
        val lastGoldenStatus: String? = null,
        val lastInitPhase: String? = null,
        val lastIndexSummary: String? = null,
        val totalEventCount: Int = 0
    )

    private const val MAX_EVENTS = 150
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val events = ArrayDeque<LiveEvent>()
    private val listeners = CopyOnWriteArrayList<(DashboardState) -> Unit>()

    @Volatile private var lastSearchSummary: String? = null
    @Volatile private var lastGoldenStatus: String? = null
    @Volatile private var lastInitPhase: String? = null
    @Volatile private var lastIndexSummary: String? = null
    @Volatile private var historyLoaded = false

    fun addListener(listener: (DashboardState) -> Unit) {
        listeners.add(listener)
        listener(currentState())
    }

    fun removeListener(listener: (DashboardState) -> Unit) {
        listeners.remove(listener)
    }

    fun record(category: String, jsonLine: String) {
        if (!EvalLogger.enabled) return
        val parsed = runCatching { JsonParser.parseString(jsonLine).asJsonObject }.getOrNull() ?: return
        val eventType = parsed.get("event")?.asString ?: "unknown"
        val ts = parsed.get("ts")?.asString ?: ""
        val id = "${ts}_${eventType}_${events.size}"
        val summary = formatSummary(eventType, parsed)
        val detail = formatDetail(eventType, parsed)

        updateHighlights(eventType, parsed, summary)

        synchronized(events) {
            events.addFirst(
                LiveEvent(id, ts, category, eventType, summary, detail)
            )
            while (events.size > MAX_EVENTS) events.removeLast()
        }
        notifyListeners()
    }

    fun loadHistoryIfNeeded(context: Context) {
        if (!EvalLogger.enabled || historyLoaded) return
        historyLoaded = true
        Thread {
            val lines = readRecentFromDisk(context, maxLines = 80)
            if (lines.isEmpty()) return@Thread
            synchronized(events) {
                val existingIds = events.map { it.id }.toSet()
                for (line in lines.asReversed()) {
                    val parsed = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    val eventType = parsed.get("event")?.asString ?: continue
                    val ts = parsed.get("ts")?.asString ?: ""
                    val id = "${ts}_${eventType}_disk"
                    if (id in existingIds) continue
                    val summary = formatSummary(eventType, parsed)
                    events.addLast(LiveEvent(id, ts, categoryFor(eventType), eventType, summary, formatDetail(eventType, parsed)))
                    updateHighlights(eventType, parsed, summary)
                }
                while (events.size > MAX_EVENTS) events.removeLast()
            }
            mainHandler.post { notifyListeners() }
        }.start()
    }

    fun currentState(): DashboardState = synchronized(events) {
        DashboardState(
            events = events.toList(),
            lastSearchSummary = lastSearchSummary,
            lastGoldenStatus = lastGoldenStatus,
            lastInitPhase = lastInitPhase,
            lastIndexSummary = lastIndexSummary,
            totalEventCount = events.size
        )
    }

    private fun notifyListeners() {
        val state = currentState()
        mainHandler.post {
            listeners.forEach { it(state) }
        }
    }

    private fun updateHighlights(eventType: String, obj: JsonObject, summary: String) {
        when (eventType) {
            "search_completed" -> {
                lastSearchSummary = summary
                obj.getAsJsonObject("golden_eval")?.get("status")?.asString?.let {
                    lastGoldenStatus = it
                }
            }
            "init_phase" -> {
                val phase = obj.get("phase")?.asString ?: return
                val ms = obj.get("duration_ms")?.asLong ?: 0L
                lastInitPhase = "$phase · ${ms}ms"
            }
            "index_completed" -> lastIndexSummary = summary
        }
    }

    private fun categoryFor(eventType: String): String = when (eventType) {
        "search_completed", "result_clicked" -> "search"
        "index_completed", "index_worker" -> "index"
        "init_phase" -> "init"
        else -> "other"
    }

    private fun formatSummary(eventType: String, obj: JsonObject): String = when (eventType) {
        "search_completed" -> {
            val q = obj.getAsJsonObject("query")?.get("raw")?.asString ?: "?"
            val r = obj.getAsJsonObject("retrieval")
            val count = r?.get("result_count")?.asInt ?: 0
            val ms = obj.getAsJsonObject("timing_ms")?.get("total")?.asLong ?: 0L
            val reason = r?.get("pass_reason")?.asString ?: ""
            val golden = obj.getAsJsonObject("golden_eval")?.get("status")?.asString
            buildString {
                append("Search \"")
                append(q.take(40))
                if (q.length > 40) append("…")
                append("\" → $count results · ${ms}ms · $reason")
                golden?.let { append(" · Golden $it") }
            }
        }
        "result_clicked" -> {
            val rank = obj.get("clicked_rank")?.asInt ?: 0
            val path = obj.get("clicked_path")?.asString?.substringAfterLast('/') ?: "?"
            "Clicked #$rank · $path"
        }
        "index_completed" -> {
            val indexed = obj.get("indexed_count")?.asInt ?: 0
            val skipped = obj.get("skipped_count")?.asInt ?: 0
            val total = obj.get("total_files")?.asInt ?: 0
            val ms = obj.get("duration_ms")?.asLong ?: 0L
            "Indexed $indexed · skipped $skipped · total $total · ${formatDuration(ms)}"
        }
        "index_worker" -> {
            val status = obj.get("status")?.asString ?: "?"
            val total = obj.get("total_files")?.asInt ?: 0
            "Worker $status${if (total > 0) " · $total files" else ""}"
        }
        "init_phase" -> {
            val phase = obj.get("phase")?.asString ?: "?"
            val ms = obj.get("duration_ms")?.asLong ?: 0L
            val count = obj.get("index_file_count")?.asInt ?: 0
            "Init $phase · ${ms}ms${if (count > 0) " · $count files" else ""}"
        }
        else -> eventType
    }

    private fun formatDetail(eventType: String, obj: JsonObject): String = when (eventType) {
        "search_completed" -> buildString {
            obj.getAsJsonObject("retrieval")?.entrySet()?.sortedBy { it.key }?.forEach { (k, v) ->
                appendLine("$k: ${v}")
            }
            obj.getAsJsonObject("timing_ms")?.entrySet()?.forEach { (k, v) ->
                appendLine("timing.$k: ${v}ms")
            }
            obj.getAsJsonArray("results")?.take(5)?.forEachIndexed { i, el ->
                val o = el.asJsonObject
                appendLine("#${i + 1} ${o.get("name")?.asString} score=${o.get("score")}")
            }
        }.trim()
        "index_completed" -> {
            val cats = obj.getAsJsonObject("category_counts")
            buildString {
                appendLine("source: ${obj.get("source")?.asString}")
                cats?.entrySet()?.forEach { appendLine("${it.key}: ${it.value}") }
            }.trim()
        }
        else -> gson.toJson(obj)
            .replace(",", ",\n")
            .take(600)
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1000}s"
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
    }

    private fun readRecentFromDisk(context: Context, maxLines: Int): List<String> {
        val eventsDir = File(context.filesDir, "ru_eval/events")
        if (!eventsDir.isDirectory) return emptyList()
        val files = eventsDir.listFiles()?.filter { it.extension == "jsonl" }?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        val lines = mutableListOf<String>()
        for (file in files) {
            val fileLines = file.readLines().filter { it.isNotBlank() }
            lines.addAll(fileLines.takeLast(maxLines - lines.size))
            if (lines.size >= maxLines) break
        }
        return lines.takeLast(maxLines)
    }
}
