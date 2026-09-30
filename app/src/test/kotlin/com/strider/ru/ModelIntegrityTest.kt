package com.strider.ru

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelIntegrityTest {

    @Test
    fun verifySha256_matchesKnownEmptyFile() {
        val temp = File.createTempFile("ru-empty", ".bin")
        try {
            temp.writeBytes(byteArrayOf())
            val expected = ModelIntegrity.sha256Hex(temp)!!
            assertTrue(ModelIntegrity.verifySha256(temp, expected))
            assertFalse(ModelIntegrity.verifySha256(temp, "deadbeef"))
        } finally {
            temp.delete()
        }
    }
}
