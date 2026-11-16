package com.yxsdc.locker.data

import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 同步包的读写：
 * 外层 zip = manifest.json（明文）+ payload.bin（加密的内层 zip）；
 * 内层 zip = data.json（DEFLATE）+ photos/<epochDay>.jpg（STORED）。
 * 读写都是单趟流式处理，不产生中间临时文件；
 * 只按白名单条目解析，所有体积都有上限，防止恶意包。
 */
object SyncArchive {
    const val MAX_PACKAGE_BYTES = 300L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
    const val MAX_DATA_JSON_BYTES = 20L * 1024 * 1024
    const val MAX_PHOTOS = 1000
    const val MAX_PHOTO_BYTES = 10L * 1024 * 1024
    private const val MAX_OUTER_ENTRIES = 64
    private const val GCM_TAG_BITS = 128

    private val photoName = Regex("photos/(\\d+)\\.jpg")
    private val random = SecureRandom()

    /**
     * 单趟写出同步包：内层 zip 直接压缩进 CipherOutputStream，再流入外层 zip 的 payload.bin。
     * payload.bin 用 DEFLATED + 0 级（允许未知大小，由 ZipOutputStream 写 data descriptor），
     * 旧版本接收端用 ZipInputStream 读取仍然兼容。
     * 照片按天逐张读取并立即写入，内存中同时只有一张。
     */
    suspend fun writePackage(
        target: File,
        manifest: SyncPackage.Manifest,
        payloadJson: ByteArray,
        photos: List<SyncPackage.SyncPhotoRef>,
        key: SecretKey,
        readPhoto: suspend (epochDay: Long) -> ByteArray?,
        expectedPhotoBytes: Long = 0L,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        require(photos.size <= MAX_PHOTOS) { "照片数量超出上限" }
        target.parentFile?.mkdirs()
        val useBytes = expectedPhotoBytes > 0
        var writtenBytes = 0L
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(SyncCrypto.IV_BYTES).also { random.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        try {
            ZipOutputStream(target.outputStream().buffered()).use { outer ->
                outer.putNextEntry(ZipEntry("manifest.json"))
                outer.write(SyncPackage.encodeManifest(manifest).toByteArray(Charsets.UTF_8))
                outer.closeEntry()

                // 载荷是密文，再压缩没有收益：0 级 DEFLATED 既避免 CPU 浪费，又允许流式写入未知大小。
                outer.setLevel(Deflater.NO_COMPRESSION)
                outer.putNextEntry(ZipEntry("payload.bin"))
                outer.write(iv)
                CipherOutputStream(CloseShieldOutputStream(outer), cipher).use { encrypted ->
                    ZipOutputStream(encrypted.buffered()).use { inner ->
                        inner.putNextEntry(ZipEntry("data.json"))
                        inner.write(payloadJson)
                        inner.closeEntry()
                        photos.forEachIndexed { index, photo ->
                            val epochDay = photo.epochDay
                            val jpeg = readPhoto(epochDay)
                                ?: throw IllegalArgumentException("照片数据缺失")
                            require(jpeg.size <= MAX_PHOTO_BYTES) { "照片体积超出上限" }
                            inner.putNextEntry(storedEntry("photos/$epochDay.jpg", jpeg))
                            inner.write(jpeg)
                            inner.closeEntry()
                            writtenBytes += jpeg.size
                            if (useBytes) {
                                onProgress(
                                    writtenBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                    expectedPhotoBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                )
                            } else {
                                onProgress(index + 1, photos.size)
                            }
                        }
                    }
                }
                outer.closeEntry()
            }
        } catch (throwable: Throwable) {
            target.delete()
            throw throwable
        }
    }

    fun readManifest(zipFile: File): SyncPackage.Manifest {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_OUTER_ENTRIES) { "同步包条目过多" }
                if (entry.name == "manifest.json") {
                    return SyncPackage.decodeManifest(
                        String(readLimited(zip, MAX_MANIFEST_BYTES), Charsets.UTF_8)
                    )
                }
                zip.closeEntry()
            }
        }
        throw IllegalArgumentException("同步包缺少清单")
    }

    /** 导入解析结果：业务数据 + 照片指纹；照片字节由 [PhotoReader] 从内层 zip 按需读取。 */
    data class ImportPayload(
        val data: SyncPackage.Payload,
        val photos: List<SyncPackage.SyncPhotoRef>,
        val innerZip: File,
    )

    /**
     * 解密同步包并把内层 zip 落到 [destInnerZip]，然后解析业务数据与照片指纹。
     * 解析过程不保留照片字节，避免大包在内存里堆积；应用时再用 [PhotoReader] 按天读取。
     */
    fun parseEncryptedPayload(
        zipFile: File,
        destInnerZip: File,
        key: SecretKey,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onBytes: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ImportPayload = try {
        decryptPayloadTo(zipFile, destInnerZip, key, onBytes)
        val (data, photos) = parseInnerZip(destInnerZip, onProgress)
        ImportPayload(data, photos, destInnerZip)
    } catch (exception: IllegalArgumentException) {
        destInnerZip.delete()
        throw exception
    } catch (throwable: Throwable) {
        // 解密后得到的数据不是合法 zip，通常说明验证码错误或文件损坏
        destInnerZip.delete()
        throw IllegalArgumentException("验证码不正确或数据已损坏", throwable)
    }

    /** 按天读取已解密内层 zip 中的照片。 */
    class PhotoReader(innerZip: File) : java.io.Closeable {
        private val zip = ZipFile(innerZip)

        fun read(epochDay: Long): ByteArray? {
            val entry = zip.getEntry("photos/$epochDay.jpg") ?: return null
            return zip.getInputStream(entry).use { readLimited(it, MAX_PHOTO_BYTES) }
        }

        /** 内层 zip 里实际存在的照片日期。 */
        fun photoDays(): Set<Long> {
            val days = mutableSetOf<Long>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                photoName.matchEntire(enumeration.nextElement().name)?.let { match ->
                    match.groupValues[1].toLongOrNull()?.let { days += it }
                }
            }
            return days
        }

        override fun close() {
            zip.close()
        }
    }

    /**
     * 核对同步包完整性：清单声明的照片必须都有对应条目，且不夹带多余条目。
     */
    fun verifyPhotoEntries(
        innerZip: File,
        payload: SyncPackage.Payload,
    ): Boolean = try {
        PhotoReader(innerZip).use { reader ->
            reader.photoDays() == payload.photos.map { it.epochDay }.toSet()
        }
    } catch (throwable: Throwable) {
        false
    }

    private fun decryptPayloadTo(
        zipFile: File,
        destZip: File,
        key: SecretKey,
        onBytes: (done: Long, total: Long) -> Unit,
    ) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_OUTER_ENTRIES) { "同步包条目过多" }
                if (entry.name == "payload.bin") {
                    val limited = LimitedInputStream(zip, MAX_PACKAGE_BYTES)
                    val iv = ByteArray(SyncCrypto.IV_BYTES)
                    readFully(limited, iv)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        key,
                        GCMParameterSpec(GCM_TAG_BITS, iv),
                    )
                    // 压缩条目没有可靠大小；0 级存储的旧包可以直接用条目长度做进度
                    val total = if (entry.size > 0) entry.size - SyncCrypto.IV_BYTES else 0L
                    var done = 0L
                    try {
                        CipherInputStream(limited, cipher).use { decrypted ->
                            destZip.outputStream().buffered().use { out ->
                                val chunk = ByteArray(64 * 1024)
                                while (true) {
                                    val count = decrypted.read(chunk)
                                    if (count < 0) break
                                    require(done + count <= MAX_PACKAGE_BYTES) {
                                        "同步数据超出体积上限"
                                    }
                                    out.write(chunk, 0, count)
                                    done += count
                                    if (total > 0) onBytes(done, total)
                                }
                            }
                        }
                    } catch (exception: IllegalArgumentException) {
                        throw exception
                    } catch (exception: Exception) {
                        throw IllegalArgumentException("验证码不正确或数据已损坏", exception)
                    }
                    return
                }
                zip.closeEntry()
            }
        }
        throw IllegalArgumentException("同步包缺少加密数据")
    }

    private fun parseInnerZip(
        innerZip: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Pair<SyncPackage.Payload, List<SyncPackage.SyncPhotoRef>> {
        var dataJson: ByteArray? = null
        val photoEntries = mutableMapOf<Long, ZipEntry>()
        var totalPhotoBytes = 0L
        ZipFile(innerZip).use { zip ->
            val entries = mutableListOf<ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) entries += enumeration.nextElement()
            require(entries.size <= MAX_PHOTOS + 16) { "同步数据条目过多" }
            entries.forEach { entry ->
                if (entry.name == "data.json") {
                    zip.getInputStream(entry).use {
                        dataJson = readLimited(it, MAX_DATA_JSON_BYTES)
                    }
                } else {
                    val match = photoName.matchEntire(entry.name) ?: return@forEach
                    val epochDay = match.groupValues[1].toLongOrNull()
                        ?: throw IllegalArgumentException("照片条目名称非法")
                    require(photoEntries.put(epochDay, entry) == null) { "照片条目重复" }
                    require(entry.size in 0..MAX_PHOTO_BYTES) { "照片体积超出上限" }
                    totalPhotoBytes += entry.size
                    require(totalPhotoBytes <= MAX_PACKAGE_BYTES) { "照片总量超出上限" }
                }
            }
        }
        val data = dataJson ?: throw IllegalArgumentException("同步数据缺少内容")
        val payload = SyncPackage.decodePayloadJson(data)
        require(payload.photos.size <= MAX_PHOTOS) { "照片数量超出上限" }
        // 只允许清单里声明过的照片条目，避免夹带额外数据
        require(photoEntries.keys.containsAll(payload.photos.map { it.epochDay })) {
            "同步数据缺少照片"
        }
        require(photoEntries.size == payload.photos.size) { "同步数据包含多余条目" }

        // 新版本在 data.json 里附带指纹，校验阶段无需读取照片内容；
        // 旧版本只有日期，逐张读取补算。
        val photos = payload.photos.mapIndexed { index, ref ->
            val resolved = if (ref.fingerprint.isNotEmpty()) {
                ref
            } else {
                val entry = photoEntries.getValue(ref.epochDay)
                val fingerprint = ZipFile(innerZip).use { zip ->
                    zip.getInputStream(entry).use {
                        DailyReport.photoFingerprint(readLimited(it, MAX_PHOTO_BYTES))
                    }
                }
                ref.copy(fingerprint = fingerprint)
            }
            onProgress(index + 1, payload.photos.size)
            resolved
        }
        return payload.copy(photos = photos) to photos
    }

    private fun copyLimited(
        input: InputStream,
        output: java.io.OutputStream,
        max: Long,
    ) {
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            total += count
            require(total <= max) { "同步数据超出体积上限" }
            output.write(chunk, 0, count)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) throw IllegalArgumentException("加密数据不完整")
            read += count
        }
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            total += count
            require(total <= max) { "同步数据超出体积上限" }
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }

    private fun storedEntry(name: String, bytes: ByteArray): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = bytes.size.toLong()
        compressedSize = bytes.size.toLong()
        crc = CRC32().apply { update(bytes) }.value
    }

    private class LimitedInputStream(
        private val input: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var total = 0L

        override fun read(): Int {
            val value = input.read()
            if (value >= 0) advance(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = input.read(buffer, offset, length)
            if (count > 0) advance(count)
            return count
        }

        override fun available(): Int = input.available()

        override fun close() = input.close()

        private fun advance(count: Int) {
            total += count
            require(total <= limit) { "同步数据超出体积上限" }
        }
    }

    /** 不让 CipherOutputStream.close() 顺带关掉外层 zip。 */
    private class CloseShieldOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }
}
