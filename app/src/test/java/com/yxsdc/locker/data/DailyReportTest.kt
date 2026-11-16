package com.yxsdc.locker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyReportTest {
    private val salt = "00112233445566778899aabbccddeeff"
    private val code = "ABCD-EFGH"

    private fun report() = DailyReport.Report(
        identityId = "id-1",
        epochDay = 20_000,
        number = "12345",
        wearMinutes = 90,
        eventCount = 2,
        photoCheck = DailyReport.PhotoCheck.PASS,
        photoFingerprint = "abcdef0123456789",
        issuedAt = 1_700_000_000_000L,
    )

    @Test
    fun encodeParseVerifyRoundTrip() {
        val payload = DailyReport.encode(report(), salt, code)
        val parsed = DailyReport.parse(payload)
        assertEquals(report(), parsed.report)
        assertTrue(DailyReport.verify(parsed, code))
        assertTrue(DailyReport.verify(parsed, "abcd efgh"))
        assertFalse(DailyReport.verify(parsed, "WXYZ-2345"))
    }

    @Test
    fun tamperedPayloadRejected() {
        val payload = DailyReport.encode(report(), salt, code)
        val parsed = DailyReport.parse(payload.replace("|12345|", "|54321|"))
        assertFalse(DailyReport.verify(parsed, code))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPhotoCheckRejected() {
        val payload = DailyReport.encode(report(), salt, code)
        DailyReport.parse(payload.replace("|P|", "|X|"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun foreignQrRejected() {
        DailyReport.parse("HELLO|world")
    }

    @Test
    fun fiveDigitCandidates() {
        assertEquals(listOf("12345"), DailyReport.findFiveDigitCandidates("随机数 12345"))
        assertEquals(listOf("12345", "23456"), DailyReport.findFiveDigitCandidates("x123456y"))
        assertTrue(DailyReport.findFiveDigitCandidates("只有四位 1234").isEmpty())
    }

    @Test
    fun photoFingerprintIsStableAndShort() {
        val a = DailyReport.photoFingerprint(byteArrayOf(1, 2, 3))
        val b = DailyReport.photoFingerprint(byteArrayOf(1, 2, 3))
        val c = DailyReport.photoFingerprint(byteArrayOf(3, 2, 1))
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(16, a.length)
    }
}
