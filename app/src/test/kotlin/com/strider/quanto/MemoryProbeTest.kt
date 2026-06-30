package com.strider.quanto

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryProbeTest {

    @Test
    fun recommendedBatchSize_usesDefaultWhenPlentyOfMemory() {
        assertEquals(16, EmbeddingGuardrails.MemoryProbe.recommendedBatchSize(16, 500L * 1024 * 1024))
    }

    @Test
    fun recommendedBatchSize_halvesWhenBelow400Mb() {
        assertEquals(8, EmbeddingGuardrails.MemoryProbe.recommendedBatchSize(16, 300L * 1024 * 1024))
    }

    @Test
    fun recommendedBatchSize_quartersWhenBelow250Mb() {
        assertEquals(4, EmbeddingGuardrails.MemoryProbe.recommendedBatchSize(16, 200L * 1024 * 1024))
    }
}
