package com.strider.ru

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualBridgeTest {

    @Test
    fun voiceTranscript_stripsCommandWords_keepsPossessives() {
        assertEquals("my aadhaar", MultilingualBridge.normalizeVoiceTranscript("find my aadhaar info please"))
    }

    @Test
    fun voiceTranscript_mapsHindiPossessive_andStripsCommands() {
        assertEquals("my pan card", MultilingualBridge.normalizeVoiceTranscript("mera PAN card dhundo"))
    }

    @Test
    fun stripStopWords_keepsPossessives() {
        val refined = MultilingualBridge.stripStopWords("find my aadhaar info")
        assertEquals("my aadhaar", refined)
    }

    @Test
    fun voiceTranscript_keepsLanguageHintForSearch() {
        assertEquals(
            "python notes in hindi",
            MultilingualBridge.normalizeVoiceTranscript("find python notes in hindi")
        )
    }

    @Test
    fun stopWords_doNotIncludePossessives() {
        assertFalse("my" in MULTILINGUAL_STOP_WORDS)
        assertFalse("mera" in MULTILINGUAL_STOP_WORDS)
        assertTrue("find" in MULTILINGUAL_STOP_WORDS)
        assertTrue("info" in MULTILINGUAL_STOP_WORDS)
    }
}
