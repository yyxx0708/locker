package com.yxsdc.locker.data

/**
 * 每日核验二维码的内容与签名。
 * 格式：LK2|salt|identityId|epochDay|number|wearMinutes|eventCount|photoCheck|photoFingerprint|issuedAt|signature
 * 签名 = HMAC-SHA256(由身份码派生的密钥, 除签名外的全部字段)，只有知道身份码的一方能验证。
 * photoFingerprint 为当日照片 JPEG 的 SHA-256 前 16 个十六进制字符，用于把日报与照片绑定。
 */
object DailyReport {
    private const val PREFIX = "LK2"

    enum class PhotoCheck(val code: String, val label: String) {
        NONE("N", "当日未上传照片"),
        PASS("P", "✓ 照片号码核验通过"),
        MISMATCH("M", "✗ 照片号码核验未通过"),
        UNKNOWN("U", "未能识别照片中的号码"),
        ;

        companion object {
            fun from(code: String): PhotoCheck? = entries.firstOrNull { it.code == code }
        }
    }

    data class Report(
        val identityId: String,
        val epochDay: Long,
        val number: String,
        val wearMinutes: Long,
        val eventCount: Int,
        val photoCheck: PhotoCheck,
        val photoFingerprint: String,
        val issuedAt: Long,
    )

    data class Parsed(
        val salt: String,
        val report: Report,
        val signature: String,
    )

    fun encode(report: Report, salt: String, code: String): String {
        val data = canonical(salt, report)
        return "$data|${SyncCrypto.hmacSign(salt, code, data)}"
    }

    fun parse(payload: String): Parsed {
        val parts = payload.trim().split("|")
        require(parts.size == 11 && parts[0] == PREFIX) { "不是 Locker 日报二维码" }
        fun bad(): Nothing = throw IllegalArgumentException("日报内容不完整")
        return Parsed(
            salt = parts[1],
            report = Report(
                identityId = parts[2].ifEmpty { bad() },
                epochDay = parts[3].toLongOrNull() ?: bad(),
                number = parts[4],
                wearMinutes = parts[5].toLongOrNull() ?: bad(),
                eventCount = parts[6].toIntOrNull() ?: bad(),
                photoCheck = PhotoCheck.from(parts[7]) ?: bad(),
                photoFingerprint = parts[8],
                issuedAt = parts[9].toLongOrNull() ?: bad(),
            ),
            signature = parts[10],
        )
    }

    fun verify(parsed: Parsed, code: String): Boolean =
        SyncCrypto.hmacSign(parsed.salt, code, canonical(parsed.salt, parsed.report)) ==
            parsed.signature

    /** 照片内容指纹（SHA-256 前 16 个十六进制字符）。 */
    fun photoFingerprint(jpeg: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(jpeg)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(16)
    }

    private fun canonical(salt: String, report: Report): String =
        listOf(
            PREFIX,
            salt,
            report.identityId,
            report.epochDay.toString(),
            report.number,
            report.wearMinutes.toString(),
            report.eventCount.toString(),
            report.photoCheck.code,
            report.photoFingerprint,
            report.issuedAt.toString(),
        ).joinToString("|")

    /** 从 OCR 文本中提取 5 位数字候选（长数字串按滑动窗口拆分）。 */
    fun findFiveDigitCandidates(text: String): List<String> =
        Regex("\\d{5,}").findAll(text)
            .flatMap { match -> match.value.windowed(5).asSequence() }
            .distinct()
            .toList()
}
