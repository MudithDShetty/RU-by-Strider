package com.strider.ru

enum class InitPhase {
    DB,
    MODEL_DOWNLOAD,
    MODEL_COPY,
    MODEL_LOAD,
    MODEL_WARMUP,
    INDEX_LOAD,
    READY
}

data class AppInitState(
    val phase: InitPhase,
    val message: String,
    /** 0–100, or -1 for indeterminate */
    val progress: Int = -1
) {
    val isComplete: Boolean get() = phase == InitPhase.READY
}
