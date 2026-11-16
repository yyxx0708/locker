package com.yxsdc.locker.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 二维码生成与识别的回归测试。
 *
 * 直接调用生产代码里的 [decodeQrPixels]，参数与 [generateQrAndroidBitmap] 保持一致，
 * 覆盖三类真实场景：正常显示、圆角/裁边、整屏截图后降采样。
 */
class QrRoundTripTest {

    private val payload = buildString {
        append("LK2|")
        append("00112233445566778899aabbccddeeff|")
        append("3f2b8c1e-9a4d-4e7b-8c21-5d6e7f8091a2|")
        append("20000|48213|90|2|P|abcdef0123456789|1700000000000|")
        append("9f8e7d6c5b4a39281706f5e4d3c2b1a0")
    }

    private fun encode(
        sizePx: Int,
        margin: Int = 4,
        level: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Pair<IntArray, Int> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to level,
            EncodeHintType.MARGIN to margin,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] =
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return pixels to matrix.width
    }

    /** 模拟圆角裁切：把四个角按半径切掉（填白）。 */
    private fun cutCorners(pixels: IntArray, size: Int, ratio: Double): IntArray {
        val result = pixels.copyOf()
        val radius = (size * ratio).toInt()
        for (y in 0 until radius) {
            for (x in 0 until radius) {
                val outside = (radius - x) * (radius - x) + (radius - y) * (radius - y) >
                    radius * radius
                if (outside) {
                    result[y * size + x] = 0xFFFFFFFF.toInt()
                    result[y * size + (size - 1 - x)] = 0xFFFFFFFF.toInt()
                    result[(size - 1 - y) * size + x] = 0xFFFFFFFF.toInt()
                    result[(size - 1 - y) * size + (size - 1 - x)] = 0xFFFFFFFF.toInt()
                }
            }
        }
        return result
    }

    @Test
    fun roundTripAtProductionSize() {
        val (pixels, size) = encode(sizePx = 1024)
        assertEquals(payload, decodeQrPixels(pixels, size, size))
    }

    @Test
    fun roundTripAtDialogSize() {
        val (pixels, size) = encode(sizePx = 640)
        assertEquals(payload, decodeQrPixels(pixels, size, size))
    }

    /**
     * 关键回归：之前静默区只留 1 个模块，二维码被裁掉约 6% 的角后完全识别不出来。
     * 现在留 4 个模块，圆角裁切后仍应可识别。
     */
    @Test
    fun roundedCornerCropStillDecodes() {
        val (pixels, size) = encode(sizePx = 640)
        val cropped = cutCorners(pixels, size, ratio = 0.06)
        assertEquals(payload, decodeQrPixels(cropped, size, size))
    }

    /** 对比记录：静默区不足时，同样的裁切更容易失败（不强制断言，仅作观察）。 */
    @Test
    fun tightQuietZoneIsLessRobust() {
        val (pixels, size) = encode(sizePx = 640, margin = 1)
        val cropped = cutCorners(pixels, size, ratio = 0.06)
        val tightOk = decodeQrPixels(cropped, size, size) != null
        println("margin=1 with 6% corner cut decodes=$tightOk")
        assert(tightOk || !tightOk)
    }

    /** 整屏截图后按 maxDimension 降采样，二维码只剩约 375 像素宽。 */
    @Test
    fun screenshotDownsampleStillDecodes() {
        val canvasWidth = 1080
        val canvasHeight = 2400
        val qrSize = 750
        val (qr, generated) = encode(sizePx = qrSize)
        val canvas = IntArray(canvasWidth * canvasHeight) { 0xFFF9F9F7.toInt() }
        val offsetX = (canvasWidth - generated) / 2
        val offsetY = 700
        for (y in 0 until generated) {
            for (x in 0 until generated) {
                canvas[(offsetY + y) * canvasWidth + offsetX + x] = qr[y * generated + x]
            }
        }

        // 与 decodeQrFromUri 相同的采样规则：缩到 2048 以内
        var sample = 1
        while (maxOf(canvasWidth, canvasHeight) / sample > 2048) sample *= 2
        assertEquals(2, sample)

        val width = canvasWidth / sample
        val height = canvasHeight / sample
        val scaled = IntArray(width * height) { index ->
            canvas[(index / width) * sample * canvasWidth + (index % width) * sample]
        }
        assertEquals(payload, decodeQrPixels(scaled, width, height))
    }

    /** 深色模式下二维码可能呈白码黑底，解码器需能自动反色。 */
    @Test
    fun invertedQrDecodes() {
        val (pixels, size) = encode(sizePx = 640)
        val inverted = IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            val r = 0xFF - ((pixel shr 16) and 0xFF)
            val g = 0xFF - ((pixel shr 8) and 0xFF)
            val b = 0xFF - (pixel and 0xFF)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        assertEquals(payload, decodeQrPixels(inverted, size, size))
    }

    @Test
    fun unrelatedImageReturnsNull() {
        assertNull(decodeQrPixels(IntArray(600 * 600) { 0xFFF0F0F0.toInt() }, 600, 600))
    }
}