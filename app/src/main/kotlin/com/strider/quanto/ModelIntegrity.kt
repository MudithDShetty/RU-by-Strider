package com.strider.quanto

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ModelIntegrity {

    private const val BUFFER_SIZE = 256 * 1024

    fun sha256Hex(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            FileInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun verifySha256(file: File, expectedHex: String): Boolean {
        if (expectedHex.isBlank()) return file.length() > 0L
        val actual = sha256Hex(file) ?: return false
        return actual.equals(expectedHex, ignoreCase = true)
    }
}
