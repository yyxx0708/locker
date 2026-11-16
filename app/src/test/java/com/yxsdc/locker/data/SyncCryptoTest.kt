package com.yxsdc.locker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCryptoTest {
    @Test
    fun generatedCodeUsesSafeAlphabetAndFormat() {
        repeat(50) {
            val code = SyncCrypto.generateCode()
            assertTrue("格式不符: $code", Regex("[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}").matches(code))
            assertFalse(code.contains('0') || code.contains('O') || code.contains('1') || code.contains('I'))
        }
    }

    @Test
    fun normalizeIgnoresCaseSeparatorsAndSpaces() {
        assertEquals("ABCDEFGH", SyncCrypto.normalizeCode(" abcd-efgh "))
        assertEquals("ABCDEFGH", SyncCrypto.normalizeCode("ABCD EFGH"))
    }

    @Test
    fun hashMatchesAndRejectsWrongCode() {
        val salt = SyncCrypto.newSalt()
        val code = "2345-6789"
        val hash = SyncCrypto.hashCode(salt, code)
        assertTrue(SyncCrypto.matches(salt, hash, "23456789"))
        assertTrue(SyncCrypto.matches(salt, hash, "2345-6789"))
        assertFalse(SyncCrypto.matches(salt, hash, "2345-6780"))
        assertFalse(SyncCrypto.matches("ff".repeat(16), hash, code))
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val salt = SyncCrypto.newSalt()
        val key = SyncCrypto.deriveKey(salt, "ABCD-EFGH")
        val plain = "同步数据 payload".toByteArray(Charsets.UTF_8)
        val encrypted = SyncCrypto.encrypt(plain, key)
        assertFalse(encrypted.contentEquals(plain))
        val decrypted = SyncCrypto.decrypt(encrypted, SyncCrypto.deriveKey(salt, "abcdefgh"))
        assertTrue(plain.contentEquals(decrypted))
    }

    @Test(expected = Exception::class)
    fun decryptWithWrongKeyFails() {
        val salt = SyncCrypto.newSalt()
        val encrypted = SyncCrypto.encrypt(
            "secret".toByteArray(Charsets.UTF_8),
            SyncCrypto.deriveKey(salt, "ABCD-EFGH"),
        )
        SyncCrypto.decrypt(encrypted, SyncCrypto.deriveKey(salt, "WXYZ-2345"))
    }
}
