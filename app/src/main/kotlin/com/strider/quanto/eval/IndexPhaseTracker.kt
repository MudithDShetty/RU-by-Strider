package com.strider.quanto.eval

/** Run-level index phase timing via wall-clock reads at phase boundaries only. */
data class IndexPhaseRecord(
    val phase: String,
    val startedAt: String,
    val endedAt: String,
    val durationMs: Long,
    val fileCount: Int = 0
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "phase" to phase,
        "started_at" to startedAt,
        "ended_at" to endedAt,
        "duration_ms" to durationMs,
        "file_count" to fileCount
    )
}

class IndexPhaseTracker {

    private data class PhaseAccum(
        var startedAt: String? = null,
        var endedAt: String? = null,
        var durationMs: Long = 0,
        var fileCount: Int = 0
    )

    private val phases = linkedMapOf<String, PhaseAccum>()
    private val sliceStartMs = mutableMapOf<String, Long>()
    private val phaseOrder = mutableListOf<String>()

    fun begin(phase: String) {
        if (!EvalLogger.enabled) return
        sliceStartMs[phase] = System.currentTimeMillis()
        val accum = phases.getOrPut(phase) {
            phaseOrder.add(phase)
            PhaseAccum()
        }
        if (accum.startedAt == null) {
            accum.startedAt = EvalEvent.nowIso()
        }
    }

    fun end(phase: String, fileCount: Int = 0) {
        if (!EvalLogger.enabled) return
        val startMs = sliceStartMs.remove(phase) ?: return
        val nowMs = System.currentTimeMillis()
        val accum = phases.getOrPut(phase) {
            phaseOrder.add(phase)
            PhaseAccum()
        }
        accum.endedAt = EvalEvent.nowIso()
        accum.durationMs += nowMs - startMs
        accum.fileCount += fileCount
    }

    fun snapshot(): List<IndexPhaseRecord> = phaseOrder.mapNotNull { name ->
        val accum = phases[name] ?: return@mapNotNull null
        val started = accum.startedAt ?: return@mapNotNull null
        val ended = accum.endedAt ?: return@mapNotNull null
        IndexPhaseRecord(
            phase = name,
            startedAt = started,
            endedAt = ended,
            durationMs = accum.durationMs,
            fileCount = accum.fileCount
        )
    }
}
