package com.strider.ru

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfTextQualityTest {

    @Test
    fun blankText_isWeak() {
        assertTrue(PdfTextQuality.isWeakPdfText(null))
        assertTrue(PdfTextQuality.isWeakPdfText(""))
        assertTrue(PdfTextQuality.isWeakPdfText("   "))
    }

    @Test
    fun shortText_isWeak() {
        assertTrue(PdfTextQuality.isWeakPdfText("hello world"))
    }

    @Test
    fun richDigitalText_isNotWeak() {
        val text = """
            This agreement is between Tanuj Kumar and Quantoo Technologies Limited.
            The parties agree to the terms of this non-disclosure agreement dated 2024.
            Confidential information shall not be disclosed without prior written consent.
        """.trimIndent()
        assertFalse(PdfTextQuality.isWeakPdfText(text))
    }

    @Test
    fun weakOcrText_threshold() {
        assertTrue(PdfTextQuality.isWeakOcrText("short"))
        assertFalse(PdfTextQuality.isWeakOcrText("Tanuj Kumar Quantoo agreement confidential NDA"))
    }

    @Test
    fun photoFolderPath_notOcrEligible() {
        val file = File("/storage/emulated/0/DCIM/Camera/scan.pdf")
        assertFalse(PdfTextQuality.isOcrEligiblePath(file))
    }

    @Test
    fun documentsPath_isOcrEligible() {
        val file = File("/storage/emulated/0/Documents/invoice.pdf")
        assertTrue(PdfTextQuality.isOcrEligiblePath(file))
    }

    @Test
    fun needsOcr_whenWeakTextInDocuments() {
        val file = File("/storage/emulated/0/Documents/scan.pdf")
        assertTrue(
            PdfTextQuality.needsOcr(
                file = file,
                rawText = "x",
                pageCount = 2,
                isEncrypted = false
            )
        )
    }

    @Test
    fun needsOcr_falseForRichText() {
        val file = File("/storage/emulated/0/Documents/report.pdf")
        val text = "Quarterly budget report for project alpha beta gamma delta epsilon zeta"
        assertFalse(
            PdfTextQuality.needsOcr(
                file = file,
                rawText = text,
                pageCount = 10,
                isEncrypted = false
            )
        )
    }
}
