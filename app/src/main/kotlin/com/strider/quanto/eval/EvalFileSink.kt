package com.strider.quanto.eval

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal class EvalFileSink(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val rootDir: File get() = File(context.filesDir, ROOT_DIR)
    private val eventsDir: File get() = File(rootDir, "events")
    private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun ensureInitialized() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                eventsDir.mkdirs()
                writeManifestIfNeeded()
                copySchemaIfNeeded()
            }.onFailure { Log.w(TAG, "Eval sink init failed: ${it.message}") }
        }
    }

    fun append(category: String, jsonLine: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                eventsDir.mkdirs()
                val date = LocalDate.now().format(dateFormatter)
                val baseName = "${category}_$date"
                val file = resolveRotatedFile(eventsDir, baseName)
                file.appendText(jsonLine + "\n")
            }.onFailure { Log.w(TAG, "Eval write failed ($category): ${it.message}") }
        }
    }

    private fun writeManifestIfNeeded() {
        val manifest = File(rootDir, "manifest.json")
        if (manifest.exists()) return
        manifest.writeText(gson.toJson(EvalSession.buildManifest(context)))
    }

    private fun copySchemaIfNeeded() {
        val schema = File(rootDir, "README.schema.json")
        if (schema.exists()) return
        schema.writeText(SCHEMA_DOC)
    }

    private fun resolveRotatedFile(dir: File, baseName: String): File {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "_${index.toString().padStart(3, '0')}"
            val file = File(dir, "$baseName$suffix.jsonl")
            if (!file.exists() || file.length() < MAX_FILE_BYTES) return file
            index++
        }
    }

    companion object {
        private const val TAG = "EvalFileSink"
        private const val ROOT_DIR = "ru_eval"
        private const val MAX_FILE_BYTES = 5L * 1024 * 1024

        private val SCHEMA_DOC = """
{
  "schema_version": 1,
  "description": "RU eval analytics — NDJSON events under events/*.jsonl",
  "export": "adb pull /data/data/com.strider.quanto/files/ru_eval ./analytics_data",
  "events": {
    "search_completed": {
      "file": "events/search_YYYY-MM-DD.jsonl",
      "fields": ["query", "context", "retrieval", "results", "timing_ms", "debug_target", "golden_eval"]
    },
    "index_completed": {
      "file": "events/index_YYYY-MM-DD.jsonl",
      "fields": ["source", "indexed_count", "skipped_count", "deleted_count", "total_files", "duration_ms", "category_counts"]
    },
    "index_worker": {
      "file": "events/index_YYYY-MM-DD.jsonl",
      "fields": ["status", "wait_for_model_ms", "total_files", "error"]
    },
    "init_phase": {
      "file": "events/init_YYYY-MM-DD.jsonl",
      "fields": ["phase", "message", "progress", "duration_ms", "is_first_model_load", "index_file_count"]
    },
    "result_clicked": {
      "file": "events/search_YYYY-MM-DD.jsonl",
      "fields": ["query", "clicked_rank", "clicked_path", "top1_path"]
    }
  }
}
""".trimIndent()
    }
}
