package com.yxsdc.locker.port

import com.yxsdc.locker.data.BackupJson
import com.yxsdc.locker.data.DailyRandomNumber
import com.yxsdc.locker.data.DailyReport
import com.yxsdc.locker.data.DefaultEventTypes
import com.yxsdc.locker.data.EventRecord
import com.yxsdc.locker.data.EventTypeEntity
import com.yxsdc.locker.data.Person
import com.yxsdc.locker.data.SyncArchive
import com.yxsdc.locker.data.SyncCrypto
import com.yxsdc.locker.data.SyncPackage
import com.yxsdc.locker.data.WearSegment
import com.yxsdc.locker.domain.StatsMode
import com.yxsdc.locker.domain.bucketBoundaries
import com.yxsdc.locker.domain.computeStats
import com.yxsdc.locker.domain.formatDuration
import com.yxsdc.locker.domain.formatDurationShort
import com.yxsdc.locker.domain.formatDurationWithSeconds
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.time.YearMonth
import java.time.ZoneId

/**
 * 生成 iOS 移植用的跨端一致性样本。
 *
 * 这些样本由 Android 端的真实实现产出，Swift 侧必须能逐字节/逐字段复现，
 * 否则两端数据无法互通。产物写入 docs/ios-port/fixtures/，随仓库提交。
 *
 * 运行：./gradlew.bat :app:testDebugUnitTest --tests '*FixturesGenerator*'
 */
class FixturesGenerator {

    /** 测试的 CWD 是 app/ 模块目录，样本要落到仓库根的 docs/ 下。 */
    private val outDir = File("../docs/ios-port/fixtures")
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 固定时间基准，保证样本可复现。2023-11-14T22:13:20Z */
    private val baseTime = 1_700_000_000_000L
    private val dayMillis = 86_400_000L

    // ---------------------------------------------------------------- 数据集

    private val persons = listOf(
        Person(id = 1L, name = "我", createdAt = baseTime),
        Person(id = 2L, name = "小A", createdAt = baseTime + 1_000L),
    )

    private val eventTypes = listOf(
        EventTypeEntity(
            id = 1L,
            typeKey = DefaultEventTypes.LOCKED_KEY,
            name = "锁射",
            colorArgb = DefaultEventTypes.LOCKED_COLOR_ARGB,
            sortOrder = 0,
            builtIn = true,
        ),
        EventTypeEntity(
            id = 2L,
            typeKey = DefaultEventTypes.UNLOCKED_KEY,
            name = "开锁射精",
            colorArgb = DefaultEventTypes.UNLOCKED_COLOR_ARGB,
            sortOrder = 1,
            builtIn = true,
        ),
        EventTypeEntity(
            id = 3L,
            typeKey = "custom-1",
            name = "自定义",
            colorArgb = 0xFF2E9E5BL,
            sortOrder = 2,
            builtIn = false,
        ),
    )

    private val segments = listOf(
        // 跨月分段：10/31 23:00 → 11/01 01:00（上海时间）
        WearSegment(id = 1L, personId = 1L, startAt = shanghai(2023, 10, 31, 23, 0), endAt = shanghai(2023, 11, 1, 1, 0)),
        WearSegment(id = 2L, personId = 1L, startAt = shanghai(2023, 11, 5, 8, 30), endAt = shanghai(2023, 11, 5, 18, 45)),
        // 佩戴中（未结束）
        WearSegment(id = 3L, personId = 1L, startAt = shanghai(2023, 11, 20, 9, 0), endAt = null),
        WearSegment(id = 4L, personId = 2L, startAt = shanghai(2023, 11, 10, 12, 0), endAt = shanghai(2023, 11, 10, 20, 0)),
    )

    private val events = listOf(
        EventRecord(id = 1L, personId = 1L, type = "locked", occurredAt = shanghai(2023, 11, 5, 12, 0), note = null),
        EventRecord(id = 2L, personId = 1L, type = "unlocked", occurredAt = shanghai(2023, 11, 5, 19, 0), note = "备注含中文"),
        EventRecord(id = 3L, personId = 1L, type = "custom-1", occurredAt = shanghai(2023, 11, 5, 20, 0), note = "  "),
        EventRecord(id = 4L, personId = 2L, type = "locked", occurredAt = shanghai(2023, 11, 10, 15, 0), note = null),
    )

    private val randomNumbers = listOf(
        DailyRandomNumber(personId = 1L, epochDay = epochDay(2023, 11, 5), number = "12345", createdAt = baseTime),
        DailyRandomNumber(personId = 2L, epochDay = epochDay(2023, 11, 5), number = "67890", createdAt = baseTime),
        DailyRandomNumber(personId = 1L, epochDay = epochDay(2023, 11, 10), number = "24680", createdAt = baseTime),
    )

    // ---------------------------------------------------------------- 工具

    private fun shanghai(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun epochDay(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day).toEpochDay()

    private fun write(name: String, content: String) {
        outDir.mkdirs()
        File(outDir, name).writeText(content, Charsets.UTF_8)
    }

    private fun write(name: String, content: ByteArray) {
        outDir.mkdirs()
        File(outDir, name).writeBytes(content)
    }

    // ---------------------------------------------------------------- 测试

    @Test
    fun generate() {
        generateBackupV4()
        generateLegacyBackups()
        generateCryptoVectors()
        generateDailyReport()
        generateStats()
        generateTimeFormat()
        generateSyncPackage()
        generateIndex()
    }

    /** 当前版本 JSON 备份（schemaVersion=4），含全部五类数据。 */
    private fun generateBackupV4() {
        val text = BackupJson.encode(
            BackupJson.BackupData(
                persons = persons,
                eventTypes = eventTypes,
                segments = segments,
                events = events,
                dailyRandomNumbers = randomNumbers,
            )
        )
        write("backup_v4.json", text)
        // 带 BOM 的变体：Android 解码时会剥离，Swift 也必须能处理
        write("backup_v4_bom.json", "﻿" + text)
    }

    /**
     * 旧版本备份。v1 没有 persons/eventTypes，分段与事件不含 personId；
     * v2 有 persons 但事件类型固定内置；v3 增加 eventTypes，仍无随机数。
     */
    private fun generateLegacyBackups() {
        val v1 = JSONObject().apply {
            put("schemaVersion", 1)
            put("exportedAt", baseTime)
            put("segments", JSONArray().apply {
                put(JSONObject().apply {
                    put("startAt", shanghai(2023, 11, 5, 8, 30))
                    put("endAt", shanghai(2023, 11, 5, 18, 45))
                })
                put(JSONObject().apply {
                    put("startAt", shanghai(2023, 11, 20, 9, 0))
                    put("endAt", JSONObject.NULL)
                })
            })
            put("events", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "locked")
                    put("occurredAt", shanghai(2023, 11, 5, 12, 0))
                    put("note", JSONObject.NULL)
                })
                put(JSONObject().apply {
                    put("type", "unlocked")
                    put("occurredAt", shanghai(2023, 11, 5, 19, 0))
                    put("note", "旧版备注")
                })
            })
        }
        write("backup_v1.json", v1.toString(2))

        val v2 = JSONObject().apply {
            put("schemaVersion", 2)
            put("exportedAt", baseTime)
            put("persons", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", 1)
                    put("name", "我")
                    put("createdAt", baseTime)
                })
            })
            put("segments", JSONArray().apply {
                put(JSONObject().apply {
                    put("personId", 1)
                    put("startAt", shanghai(2023, 11, 5, 8, 30))
                    put("endAt", shanghai(2023, 11, 5, 18, 45))
                })
            })
            put("events", JSONArray().apply {
                put(JSONObject().apply {
                    put("personId", 1)
                    put("type", "locked")
                    put("occurredAt", shanghai(2023, 11, 5, 12, 0))
                    put("note", JSONObject.NULL)
                })
            })
        }
        write("backup_v2.json", v2.toString(2))

        val v3 = JSONObject().apply {
            put("schemaVersion", 3)
            put("exportedAt", baseTime)
            put("persons", JSONArray().apply {
                persons.forEach { person ->
                    put(JSONObject().apply {
                        put("id", person.id)
                        put("name", person.name)
                        put("createdAt", person.createdAt)
                    })
                }
            })
            put("eventTypes", JSONArray().apply {
                eventTypes.forEach { type ->
                    put(JSONObject().apply {
                        put("typeKey", type.typeKey)
                        put("name", type.name)
                        put("colorArgb", type.colorArgb)
                        put("sortOrder", type.sortOrder)
                        put("builtIn", type.builtIn)
                    })
                }
            })
            put("segments", JSONArray().apply {
                segments.forEach { segment ->
                    put(JSONObject().apply {
                        put("personId", segment.personId)
                        put("startAt", segment.startAt)
                        if (segment.endAt == null) put("endAt", JSONObject.NULL)
                        else put("endAt", segment.endAt)
                    })
                }
            })
            put("events", JSONArray().apply {
                events.forEach { event ->
                    put(JSONObject().apply {
                        put("personId", event.personId)
                        put("type", event.type)
                        put("occurredAt", event.occurredAt)
                        if (event.note.isNullOrBlank()) put("note", JSONObject.NULL)
                        else put("note", event.note)
                    })
                }
            })
        }
        write("backup_v3.json", v3.toString(2))
    }

    /** 密码学中间值：Swift 侧必须逐字节复现，否则同步包无法互通。 */
    private fun generateCryptoVectors() {
        val salt = "00112233445566778899aabbccddeeff"
        val code = "ABCD-EFGH"

        val derivedKey = SyncCrypto.deriveKey(salt, code).encoded
        val payload = "LK2|$salt|id-1|19666|12345|120|3|N||1700000000000"

        val vectors = JSONObject().apply {
            put("_comment", "所有十六进制均为小写。Swift 实现必须逐字节复现。")
            put("salt", salt)
            put("code", code)
            put("normalizedCode", SyncCrypto.normalizeCode(code))
            put("normalizedCodeLowercaseInput", SyncCrypto.normalizeCode("abcd-efgh"))
            put("normalizedCodeWithSpaces", SyncCrypto.normalizeCode("  ab cd ef gh  "))
            put("codeHash", SyncCrypto.hashCode(salt, code))
            put("kdf", JSONObject().apply {
                put("algorithm", "PBKDF2-HMAC-SHA256")
                put("iterations", 120_000)
                put("outputBits", 256)
                put("saltInput", "十六进制字符串本身的 UTF-8 字节，不是解码后的 16 字节")
                put("saltUtf8Bytes", salt.toByteArray(Charsets.UTF_8).size)
            })
            put("derivedKeyHex", derivedKey.toHex())
            put("hmacPayload", payload)
            put("hmacSignHex", SyncCrypto.hmacSign(salt, code, payload))
            put("hmacTruncatedBytes", 16)
        }
        write("crypto_vectors.json", vectors.toString(2))

        // AES-GCM 往返样本：固定 IV，验证 Swift 能解 Android 的密文
        val key = SyncCrypto.deriveKey(salt, code)
        val plain = "你好，Locker 同步数据".toByteArray(Charsets.UTF_8)
        val blob = SyncCrypto.encrypt(plain, key)
        val aes = JSONObject().apply {
            put("salt", salt)
            put("code", code)
            put("ivHex", blob.copyOfRange(0, SyncCrypto.IV_BYTES).toHex())
            put("ciphertextWithTagHex", blob.copyOfRange(SyncCrypto.IV_BYTES, blob.size).toHex())
            put("plaintextUtf8", String(plain, Charsets.UTF_8))
            put("plaintextHex", plain.toHex())
            put("ivBytes", SyncCrypto.IV_BYTES)
            put("tagBits", 128)
            put("layout", "IV(12) || ciphertext || tag(16)")
        }
        write("aes_gcm_vectors.json", aes.toString(2))
    }

    /** 日报二维码：LK2 11 字段格式 + HMAC 签名。 */
    private fun generateDailyReport() {
        val salt = "00112233445566778899aabbccddeeff"
        val code = "ABCD-EFGH"
        val jpeg = ByteArray(256) { (it % 251).toByte() }
        val fingerprint = DailyReport.photoFingerprint(jpeg)

        val report = DailyReport.Report(
            identityId = "id-1",
            epochDay = epochDay(2023, 11, 5),
            number = "12345",
            wearMinutes = 615,
            eventCount = 3,
            photoCheck = DailyReport.PhotoCheck.PASS,
            photoFingerprint = fingerprint,
            issuedAt = baseTime,
        )
        val payload = DailyReport.encode(report, salt, code)

        val json = JSONObject().apply {
            put("salt", salt)
            put("code", code)
            put("payload", payload)
            put("fields", JSONArray().apply {
                put("LK2"); put("salt"); put("identityId"); put("epochDay")
                put("number"); put("wearMinutes"); put("eventCount")
                put("photoCheck"); put("photoFingerprint"); put("issuedAt"); put("signature")
            })
            put("photoCheckCodes", JSONObject().apply {
                put("NONE", "N"); put("PASS", "P"); put("MISMATCH", "M"); put("UNKNOWN", "U")
            })
            put("photoJpegHex", jpeg.toHex())
            put("photoFingerprint", fingerprint)
            put("fingerprintRule", "SHA-256 的十六进制字符串取前 16 个字符")
            put("ocrSample", "订单号 98765 金额 12345 合计 55555")
            put("ocrCandidates", JSONArray(DailyReport.findFiveDigitCandidates("订单号 98765 金额 12345 合计 55555")))
        }
        write("daily_report.json", json.toString(2))
    }

    /** 统计结果：跨月裁剪、逐桶累加、未知事件兜底。 */
    private fun generateStats() {
        val anchor = YearMonth.of(2023, 11)
        // 固定 now：2023-11-25T12:00 上海时间，让「佩戴中」分段有确定长度
        val now = shanghai(2023, 11, 25, 12, 0)

        val monthResult = computeStats(
            segments = segments,
            events = events,
            eventTypes = eventTypes,
            mode = StatsMode.MONTH,
            anchor = anchor,
            zone = zone,
            now = now,
        )
        val yearResult = computeStats(
            segments = segments,
            events = events,
            eventTypes = eventTypes,
            mode = StatsMode.YEAR,
            anchor = anchor,
            zone = zone,
            now = now,
        )

        val json = JSONObject().apply {
            put("_comment", "时区固定 Asia/Shanghai；now 固定为 2023-11-25T12:00+08:00")
            put("zone", "Asia/Shanghai")
            put("now", now)
            put("month", JSONObject().apply {
                put("anchor", "2023-11")
                put("boundaries", JSONArray(bucketBoundaries(StatsMode.MONTH, anchor, zone)))
                put("totalWearMillis", monthResult.totalWearMillis)
                put("wearBuckets", JSONArray(monthResult.wearBuckets))
                put("bucketLabels", JSONArray(monthResult.bucketLabels))
                put("eventStats", JSONArray().apply {
                    monthResult.eventStats.forEach { stat ->
                        put(JSONObject().apply {
                            put("typeKey", stat.type.typeKey)
                            put("name", stat.type.name)
                            put("count", stat.count)
                            put("buckets", JSONArray(stat.buckets))
                        })
                    }
                })
                put("segmentDetails", JSONArray().apply {
                    monthResult.segmentDetails.forEach { detail ->
                        put(JSONObject().apply {
                            put("startAt", detail.segment.startAt)
                            put("endAt", detail.segment.endAt ?: JSONObject.NULL)
                            put("clippedMillis", detail.clippedMillis)
                            put("crossesPeriodBoundary", detail.crossesPeriodBoundary)
                        })
                    }
                })
            })
            put("year", JSONObject().apply {
                put("anchor", "2023")
                put("boundaries", JSONArray(bucketBoundaries(StatsMode.YEAR, anchor, zone)))
                put("totalWearMillis", yearResult.totalWearMillis)
                put("wearBuckets", JSONArray(yearResult.wearBuckets))
                put("bucketLabels", JSONArray(yearResult.bucketLabels))
            })
        }
        write("stats_expected.json", json.toString(2))
    }

    /** 时长格式化：所有分支的预期输出。 */
    private fun generateTimeFormat() {
        val cases = listOf(
            0L, 1_000L, 59_000L, 60_000L, 61_000L,
            3_600_000L, 3_661_000L,
            86_400_000L, 86_400_000L + 3_600_000L, 86_400_000L + 3_661_000L,
            2 * 86_400_000L + 5 * 3_600_000L + 7 * 60_000L,
            -1L,
        )
        val json = JSONObject().apply {
            put("_comment", "formatDuration 精确到分；formatDurationWithSeconds 保留秒；formatDurationShort 用于图表")
            put("cases", JSONArray().apply {
                cases.forEach { millis ->
                    put(JSONObject().apply {
                        put("millis", millis)
                        put("duration", formatDuration(millis))
                        put("durationWithSeconds", formatDurationWithSeconds(millis))
                        put("durationShort", formatDurationShort(millis))
                    })
                }
            })
        }
        write("time_format.json", json.toString(2))
    }

    /** 同步包 zip：Swift 必须能解密并解析出同样的业务数据。 */
    private fun generateSyncPackage() {
        val salt = "00112233445566778899aabbccddeeff"
        val code = "ABCD-EFGH"
        val key = SyncCrypto.deriveKey(salt, code)

        val photoA = ByteArray(512) { (it % 251).toByte() }
        val photoB = ByteArray(1024) { ((it * 7) % 251).toByte() }
        val photos = mapOf(
            epochDay(2023, 11, 5) to photoA,
            epochDay(2023, 11, 10) to photoB,
        )

        val payload = SyncPackage.Payload(
            personName = "小A",
            eventTypes = eventTypes,
            segments = segments.filter { it.personId == 2L },
            events = events.filter { it.personId == 2L },
            randomNumbers = randomNumbers.filter { it.personId == 2L },
            photos = photos.map { (day, bytes) ->
                SyncPackage.SyncPhotoRef(day, DailyReport.photoFingerprint(bytes))
            },
        )

        val manifest = SyncPackage.Manifest(
            identityId = "id-1",
            identityName = "小A",
            codeSalt = salt,
            codeHash = SyncCrypto.hashCode(salt, code),
            exportedAt = baseTime,
            fromEpochDay = epochDay(2023, 11, 5),
            toEpochDay = epochDay(2023, 11, 10),
        )

        val target = File(outDir, "sync_package.zip")
        outDir.mkdirs()
        runBlocking {
            SyncArchive.writePackage(
                target = target,
                manifest = manifest,
                payloadJson = SyncPackage.encodePayloadJson(payload),
                photos = payload.photos,
                key = key,
                readPhoto = { day -> photos[day] },
            )
        }

        // 配套说明：Swift 用 salt+code 派生密钥后解密，再比对下面的期望值
        val expected = JSONObject().apply {
            put("_comment", "用 salt+code 派生密钥，解出 payload.bin 得到内层 zip")
            put("salt", salt)
            put("code", code)
            put("manifest", JSONObject().apply {
                put("identityId", manifest.identityId)
                put("identityName", manifest.identityName)
                put("codeSalt", manifest.codeSalt)
                put("codeHash", manifest.codeHash)
                put("exportedAt", manifest.exportedAt)
                put("fromEpochDay", manifest.fromEpochDay)
                put("toEpochDay", manifest.toEpochDay)
            })
            put("outerEntries", JSONArray(listOf("manifest.json", "payload.bin")))
            put("innerEntries", JSONArray(listOf("data.json") + photos.keys.sorted().map { "photos/$it.jpg" }))
            put("payloadJson", SyncPackage.encodePayloadJson(payload).toString(Charsets.UTF_8))
            put("photos", JSONObject().apply {
                photos.forEach { (day, bytes) ->
                    put(day.toString(), JSONObject().apply {
                        put("hex", bytes.toHex())
                        put("fingerprint", DailyReport.photoFingerprint(bytes))
                        put("size", bytes.size)
                    })
                }
            })
        }
        write("sync_package_expected.json", expected.toString(2))
    }

    /** 样本索引。 */
    private fun generateIndex() {
        val index = JSONObject().apply {
            put("_comment", "由 Android 实现生成，Swift 侧必须逐字段复现")
            put("generator", "app/src/test/java/com/yxsdc/locker/port/FixturesGenerator.kt")
            put("generatedFromVersion", "2.2.5")
            put("fixtures", JSONArray().apply {
                put(JSONObject().apply {
                    put("file", "backup_v4.json")
                    put("what", "JSON 备份 v4，含 2 人物 / 3 事件类型 / 4 分段 / 4 事件 / 3 随机数")
                    put("swiftTest", "解析 → 重新编码 → 与原文字段等价")
                })
                put(JSONObject().apply {
                    put("file", "backup_v4_bom.json")
                    put("what", "同上但带 UTF-8 BOM")
                    put("swiftTest", "解析时必须剥离 BOM")
                })
                put(JSONObject().apply {
                    put("file", "backup_v1.json")
                    put("what", "旧版备份，无 persons/eventTypes，分段无 personId")
                    put("swiftTest", "降级：归入 id=1 的「我」，使用内置事件类型")
                })
                put(JSONObject().apply {
                    put("file", "backup_v2.json")
                    put("what", "有 persons，事件类型固定内置")
                    put("swiftTest", "解析出 1 人物 + 2 内置类型")
                })
                put(JSONObject().apply {
                    put("file", "backup_v3.json")
                    put("what", "有 eventTypes，无随机数")
                    put("swiftTest", "解析出空随机数列表")
                })
                put(JSONObject().apply {
                    put("file", "crypto_vectors.json")
                    put("what", "身份码归一化、SHA-256 哈希、PBKDF2 派生密钥、HMAC 签名")
                    put("swiftTest", "逐字节比对 hex")
                })
                put(JSONObject().apply {
                    put("file", "aes_gcm_vectors.json")
                    put("what", "AES-256-GCM 密文样本")
                    put("swiftTest", "用派生密钥解密得到原明文")
                })
                put(JSONObject().apply {
                    put("file", "daily_report.json")
                    put("what", "LK2 日报二维码内容与签名")
                    put("swiftTest", "解析 11 字段 + 验签 + 重新编码得到同一字符串")
                })
                put(JSONObject().apply {
                    put("file", "stats_expected.json")
                    put("what", "月/年统计的边界、桶、总量、事件分布")
                    put("swiftTest", "computeStats 结果逐字段相等")
                })
                put(JSONObject().apply {
                    put("file", "time_format.json")
                    put("what", "时长格式化全分支输出")
                    put("swiftTest", "格式化函数输出字符串相等")
                })
                put(JSONObject().apply {
                    put("file", "sync_package.zip")
                    put("what", "真实同步包（外层 zip + 加密载荷 + 内层 zip + 照片）")
                    put("swiftTest", "读 manifest → 派生密钥 → 解密 → 解析内层 zip → 比对照片指纹")
                })
                put(JSONObject().apply {
                    put("file", "sync_package_expected.json")
                    put("what", "同步包的期望解析结果")
                    put("swiftTest", "与解密结果逐字段比对")
                })
            })
        }
        write("index.json", index.toString(2))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
