package com.strider.quanto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryEnricherTest {

    @Test
    fun namePlusDocType_isGeneral_notPersonName() {
        assertEquals(QueryType.GENERAL, resolveQueryType("Mudith Certificate", listOf("mudith", "certificate")))
        assertEquals(QueryType.GENERAL, resolveQueryType("Certificate Mudith", listOf("certificate", "mudith")))
        assertEquals(QueryType.GENERAL, resolveQueryType("Sarah passport", listOf("sarah", "passport")))
    }

    @Test
    fun purePersonName_staysPersonName() {
        assertEquals(QueryType.PERSON_NAME, resolveQueryType("Mudith", listOf("mudith")))
        assertEquals(QueryType.PERSON_NAME, resolveQueryType("John Smith", listOf("john", "smith")))
    }

    @Test
    fun docTypeToken_recognized() {
        assertTrue(isDocTypeQueryToken("certificate"))
        assertTrue(isDocTypeQueryToken("passport"))
        assertFalse(isDocTypeQueryToken("mudith"))
    }

    @Test
    fun embeddingTokenOrder_isStable() {
        assertEquals(
            normalizeTokenOrderForEmbedding("mudith certificate"),
            normalizeTokenOrderForEmbedding("certificate mudith")
        )
        assertEquals("certificate mudith", normalizeTokenOrderForEmbedding("mudith certificate"))
    }
}
