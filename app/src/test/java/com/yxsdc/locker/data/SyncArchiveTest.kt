package com.yxsdc.locker.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class SyncArchiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    private fun manifest() = SyncPackage.Manifest(
        identityId = "id-1",
        identityName = "小A",
        codeSalt = "aa",
        codeHash = "bb",
        exportedAt = 1_700_000_000_000L,
        fromEpochDay = 20_000,
        toEpochDay = 20_001,
    )

    private val photoBytes = mapOf(
        20_000L to byteArrayOf(1, 2, 3),
        20_001L to ByteArray(2048) { (it % 251).toByte() },
    )

    private fun photoRefs() = photoBytes.map { (epochDay, jpeg) ->
        SyncPackage.SyncPhotoRef(epochDay, DailyReport.photoFingerprint(jpeg))
    }

    private fun payload() = SyncPackage.Payload(
        personName = "小A",
        eventTypes = listOf(
            EventTypeEntity(
                typeKey = "custom_uuid",
                name = "自定义",
                colorArgb = 0xFF2A78D6,
                sortOrder = 3,
                builtIn = false,
            ),
        ),
        segments = listOf(WearSegment(personId = 0, startAt = 1_000, endAt = 2_000)),
        events = listOf(
            EventRecord(personId = 0, type = "custom_uuid", occurredAt = 1_500, note = "备注"),
        ),
        randomNumbers = listOf(
            DailyRandomNumber(personId = 0, epochDay = 20_000, number = "12345", createdAt = 1),
        ),
        photos = photoRefs(),
    )

    private fun writeNewPackage(target: File): File = runBlocking {
        val payload = payload()
        SyncArchive.writePackage(
            target = target,
            manifest = manifest(),
            payloadJson = SyncPackage.encodePayloadJson(payload),
            photos = payload.photos,
            key = key,
            readPhoto = { photoBytes[it] },
        )
        target
    }

    private fun parse(target: File, parseKey: SecretKey = key): SyncArchive.ImportPayload =
        SyncArchive.parseEncryptedPayload(
            zipFile = target,
            destInnerZip = File(folder.root, "inner-${System.nanoTime()}.zip"),
            key = parseKey,
        )

    @Test
    fun roundTripPreservesDataAndPhotos() {
        val payload = payload()
        val target = writeNewPackage(File(folder.root, "new.zip"))
        assertEquals(manifest(), SyncArchive.readManifest(target))

        val parsed = parse(target)
        assertEquals(payload.personName, parsed.data.personName)
        assertEquals(payload.eventTypes, parsed.data.eventTypes)
        assertEquals(payload.segments, parsed.data.segments)
        assertEquals(payload.events, parsed.data.events)
        assertEquals(payload.randomNumbers, parsed.data.randomNumbers)
        assertTrue(parsed.data.photos.isNotEmpty())
        assertEquals(payload.photos, parsed.photos)

        SyncArchive.PhotoReader(parsed.innerZip).use { reader ->
            payload.photos.forEach { photo ->
                assertArrayEquals(photoBytes.getValue(photo.epochDay), reader.read(photo.epochDay))
            }
        }
        parsed.innerZip.delete()
    }

    @Test
    fun reportsProgressForEveryPhoto() {
        val payload = payload()
        val exported = mutableListOf<Pair<Int, Int>>()
        val target = File(folder.root, "new.zip")
        runBlocking {
            SyncArchive.writePackage(
                target = target,
                manifest = manifest(),
                payloadJson = SyncPackage.encodePayloadJson(payload),
                photos = payload.photos,
                key = key,
                readPhoto = { photoBytes[it] },
            ) { done, total -> exported += done to total }
        }
        assertEquals(listOf(1 to 2, 2 to 2), exported)

        val parsedProgress = mutableListOf<Pair<Int, Int>>()
        SyncArchive.parseEncryptedPayload(
            zipFile = target,
            destInnerZip = File(folder.root, "inner-progress.zip"),
            key = key,
            onProgress = { done, total -> parsedProgress += done to total },
        )
        assertEquals(listOf(1 to 2, 2 to 2), parsedProgress)
    }

    @Test
    fun photosAreStoredAndJsonIsDeflatedInInnerZip() {
        val target = writeNewPackage(File(folder.root, "new.zip"))
        val (method, payloadBytes) = readEntry(target, "payload.bin")
        // 外层载荷是密文，0 级 DEFLATED（流式写入未知大小）
        assertEquals(ZipEntry.DEFLATED, method)
        val innerZip = SyncCrypto.decrypt(payloadBytes, key)
        ZipInputStream(innerZip.inputStream()).use { zip ->
            val methods = mutableMapOf<String, Int>()
            while (true) {
                val entry = zip.nextEntry ?: break
                methods[entry.name] = entry.method
                zip.closeEntry()
            }
            assertEquals(ZipEntry.DEFLATED, methods["data.json"])
            assertEquals(ZipEntry.STORED, methods["photos/20000.jpg"])
            assertEquals(ZipEntry.STORED, methods["photos/20001.jpg"])
        }
    }

    /** 旧版本写出的包（payload.bin STORED、照片 DEFLATED）必须仍可读取。 */
    @Test
    fun readsLegacyStoredPayloadPackage() {
        val payload = payload()
        val inner = File(folder.root, "inner.zip")
        ZipOutputStream(inner.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(SyncPackage.encodePayloadJson(payload))
            zip.closeEntry()
            payload.photos.forEach { photo ->
                zip.putNextEntry(ZipEntry("photos/${photo.epochDay}.jpg"))
                zip.write(photoBytes.getValue(photo.epochDay))
                zip.closeEntry()
            }
        }
        val encrypted = SyncCrypto.encrypt(inner.readBytes(), key)
        val target = File(folder.root, "legacy.zip")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(SyncPackage.encodeManifest(manifest()).toByteArray())
            zip.closeEntry()
            val entry = ZipEntry("payload.bin").apply {
                method = ZipEntry.STORED
                size = encrypted.size.toLong()
                compressedSize = encrypted.size.toLong()
                crc = CRC32().apply { update(encrypted) }.value
            }
            zip.putNextEntry(entry)
            zip.write(encrypted)
            zip.closeEntry()
        }

        val parsed = parse(target)
        assertEquals(payload.segments, parsed.data.segments)
        SyncArchive.PhotoReader(parsed.innerZip).use { reader ->
            payload.photos.forEach { photo ->
                assertArrayEquals(photoBytes.getValue(photo.epochDay), reader.read(photo.epochDay))
            }
        }
        parsed.innerZip.delete()
    }

    @Test
    fun verifyPhotoEntriesMatchesManifest() {
        val target = writeNewPackage(File(folder.root, "new.zip"))
        val parsed = parse(target)
        assertTrue(SyncArchive.verifyPhotoEntries(parsed.innerZip, parsed.data))

        // 清单声明了不存在的照片时判定为不一致
        val extra = parsed.data.copy(
            photos = parsed.data.photos + SyncPackage.SyncPhotoRef(20_002, "x"),
        )
        assertTrue(!SyncArchive.verifyPhotoEntries(parsed.innerZip, extra))
        parsed.innerZip.delete()
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongKeyIsRejected() {
        val target = writeNewPackage(File(folder.root, "new.zip"))
        SyncArchive.parseEncryptedPayload(
            zipFile = target,
            destInnerZip = File(folder.root, "inner-wrong.zip"),
            key = SecretKeySpec(ByteArray(32) { 9 }, "AES"),
        )
    }

    private fun readEntry(file: File, name: String): Pair<Int, ByteArray> {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) {
                    return entry.method to zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        error("缺少条目 $name")
    }
}
