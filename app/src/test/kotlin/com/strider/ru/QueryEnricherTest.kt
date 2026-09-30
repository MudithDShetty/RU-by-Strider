package com.strider.ru

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
        assertTrue(isDocTypeQueryToken("pancard"))
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

    @Test
    fun resolveOwnerIntent_myMapsToSelf() {
        val (intent, tokens) = resolveOwnerIntent("my pancard", listOf("mudith", "shetty"))
        assertEquals(OwnerIntent.SELF, intent)
        assertEquals(listOf("mudith", "shetty"), tokens)
    }

    @Test
    fun resolveOwnerIntent_meraMapsToSelf() {
        val (intent, _) = resolveOwnerIntent("mera aadhaar", listOf("mudith", "shetty"))
        assertEquals(OwnerIntent.SELF, intent)
    }

    @Test
    fun buildSelfOwnerSearchForms_replacesMyWithFirstName() {
        val forms = buildSelfOwnerSearchForms(
            coreTokens = listOf("my", "pancard"),
            userNameTokens = listOf("mudith", "shetty"),
            embedFallback = "pancard"
        )
        assertEquals(listOf("pancard"), forms.coreTokens)
        assertEquals("pancard", forms.embedQueryText)
        assertEquals(listOf("mudith", "pancard"), forms.retrievalPrimary)
        assertEquals(listOf(listOf("shetty", "pancard")), forms.retrievalAlternates)
    }

    @Test
    fun buildSelfOwnerSearchForms_middleNameGetsAlternatePass() {
        val forms = buildSelfOwnerSearchForms(
            coreTokens = listOf("my", "pan"),
            userNameTokens = listOf("mudith", "raj", "shetty"),
            embedFallback = "pan"
        )
        assertEquals(listOf("pan"), forms.coreTokens)
        assertEquals("pan", forms.embedQueryText)
        assertEquals(listOf("mudith", "pan"), forms.retrievalPrimary)
        assertTrue(forms.retrievalAlternates.contains(listOf("raj", "pan")))
        assertTrue(forms.retrievalAlternates.contains(listOf("shetty", "pan")))
    }

    @Test
    fun buildSelfOwnerSearchForms_myAadhar_ranksByDocNotName() {
        val forms = buildSelfOwnerSearchForms(
            coreTokens = listOf("my", "aadhar"),
            userNameTokens = listOf("mudith", "shetty"),
            embedFallback = "aadhar"
        )
        assertEquals(listOf("aadhar"), forms.coreTokens)
        assertEquals("aadhar", forms.embedQueryText)
        assertEquals(listOf("mudith", "aadhar"), forms.retrievalPrimary)
    }

    @Test
    fun stripPossessiveMarkers_removesMyAndMera() {
        assertEquals(listOf("pancard"), stripPossessiveMarkers(listOf("my", "pancard")))
        assertEquals(listOf("aadhaar"), stripPossessiveMarkers(listOf("mera", "aadhaar")))
    }

    @Test
    fun userProfile_primaryAndAlternateTokens() {
        assertEquals(listOf("mudith", "shetty"), UserProfile.primaryIdentityTokens(listOf("mudith", "raj", "shetty")))
        assertEquals(listOf("raj"), UserProfile.alternateIdentityTokens(listOf("mudith", "raj", "shetty")))
        assertEquals("mudith", UserProfile.firstNameToken(listOf("mudith", "shetty")))
    }
}
