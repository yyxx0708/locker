package com.yxsdc.locker.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 同步身份码的生成、哈希与加密。
 * 身份码同时充当 zip 载荷的加密钥匙：验证码错误时既无法通过哈希校验，也无法解密。
 */
object SyncCrypto {
    /** 去掉易混淆的 0/O、1/I */
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val CODE_LENGTH = 8
    private const val KDF_ITERATIONS = 120_000
    private const val GCM_TAG_BITS = 128
    const val IV_BYTES = 12

    private val random = SecureRandom()

    fun generateCode(): String {
        val chars = CharArray(CODE_LENGTH) {
            ALPHABET[random.nextInt(ALPHABET.length)]
        }
        return chars.concatToString().chunked(4).joinToString("-")
    }

    /** 输入归一化：忽略大小写、连字符与空白。 */
    fun normalizeCode(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }

    fun newSalt(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /** 用于 zip 清单里的身份校验，不写明文码。 */
    fun hashCode(salt: String, code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(Charsets.UTF_8))
        digest.update(normalizeCode(code).toByteArray(Charsets.UTF_8))
        return digest.digest().toHex()
    }

    fun matches(salt: String, codeHash: String, input: String): Boolean =
        hashCode(salt, input) == codeHash

    /** 由身份码派生 AES-256 密钥（PBKDF2-HMAC-SHA256）。 */
    fun deriveKey(salt: String, code: String): SecretKey {
        val spec = PBEKeySpec(
            normalizeCode(code).toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            KDF_ITERATIONS,
            256,
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** 日报二维码的 HMAC-SHA256 签名（取前 16 字节）。 */
    fun hmacSign(salt: String, code: String, data: String): String {
        val key = SecretKeySpec(deriveKey(salt, code).encoded, "HmacSHA256")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(key)
        val digest = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return digest.copyOf(16).toHex()
    }

    /** 返回 IV 前缀 + AES-GCM 密文（含认证标签）。 */
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray, key: SecretKey): ByteArray {
        require(blob.size > IV_BYTES) { "加密数据不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
