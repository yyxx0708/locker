package com.yxsdc.locker.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.max

/**
 * 二维码的静默区（四周白边），单位是模块。
 * 规范要求 4；之前用 1 时，只要截图带圆角或稍微裁边就完全识别不出来。
 */
private const val QUIET_ZONE_MODULES = 4

/** 单次解码允许的最大像素数，约 16MB 的 ARGB 缓冲。 */
private const val MAX_DECODE_PIXELS = 4_000_000L

/** 生成二维码的 Android 位图，供分享卡等非 Compose 场景使用。 */
fun generateQrAndroidBitmap(
    content: String,
    sizePx: Int = 640,
    errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to errorCorrection,
        EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] =
                if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(
        pixels,
        matrix.width,
        matrix.height,
        Bitmap.Config.RGB_565,
    )
}.getOrNull()

/** 生成二维码位图；内容不合法时返回 null。 */
fun generateQrBitmap(content: String, sizePx: Int = 640): ImageBitmap? =
    generateQrAndroidBitmap(content, sizePx)?.asImageBitmap()

/**
 * 从相册/截图 URI 中解码二维码。
 *
 * 截图里二维码常常只占画面一小块：采样过度会让每个模块不足 2 像素而识别失败，
 * 不采样又可能耗尽内存。因此按「更清晰 → 基准 → 更省内存」的顺序尝试若干档，
 * 每档都限制像素总量，任一档成功即返回。
 */
fun decodeQrFromUri(context: Context, uri: Uri, maxDimension: Int = 2048): String? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // 注意：inJustDecodeBounds 模式下 decodeStream 固定返回 null，
    // 所以这里只能对「打开流」判空，不能对整个 use 的结果判空（否则永远提前返回）。
    val boundsStream = resolver.openInputStream(uri) ?: return@runCatching null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val largest = max(bounds.outWidth, bounds.outHeight)
    // 基准档：缩到 maxDimension 以内
    var baseSample = 1
    while (largest / baseSample > maxDimension) baseSample *= 2

    // 先试比基准更清晰的一档（截图场景最常见），再试基准，最后试更小的
    val attempts = buildList {
        if (baseSample > 1) add(baseSample / 2)
        add(baseSample)
        add(baseSample * 2)
    }.distinct()

    for (sample in attempts) {
        val width = bounds.outWidth / sample
        val height = bounds.outHeight / sample
        if (width <= 0 || height <= 0) continue
        if (width.toLong() * height > MAX_DECODE_PIXELS) continue
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 用 ARGB_8888：RGB_565 量化后低对比度截图容易丢失模块边界
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: continue
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decodeQrPixels(pixels, bitmap.width, bitmap.height)?.let { return@runCatching it }
        } finally {
            bitmap.recycle()
        }
    }
    null
}.getOrNull()

/** 从图片（如对方发来的二维码截图）中解码二维码内容；找不到时返回 null。 */
fun decodeQrBitmap(bitmap: Bitmap): String? = runCatching {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    decodeQrPixels(pixels, width, height)
}.getOrNull()

/**
 * 纯像素层面的二维码解码：两种二值化 × 正色/反色。
 * 抽成纯函数，便于在 JVM 单测里覆盖「圆角裁切」「整屏截图降采样」等真实场景。
 *
 * 反色不依赖 zxing 的 ALSO_INVERTED（3.5.x 上行为不稳定），而是先把像素取反再解一次，
 * 覆盖深色模式截图、白码黑底的情况。
 */
internal fun decodeQrPixels(pixels: IntArray, width: Int, height: Int): String? {
    decodeOnce(pixels, width, height)?.let { return it }
    return decodeOnce(invertPixels(pixels), width, height)
}

private fun decodeOnce(pixels: IntArray, width: Int, height: Int): String? {
    val source = RGBLuminanceSource(width, height, pixels)
    val binarizers = listOf(
        // 截图里是硬边块的场景更稳
        HybridBinarizer(source),
        // 低对比度、带渐变的照片靠全局直方图兜底
        GlobalHistogramBinarizer(source),
    )
    for (binarizer in binarizers) {
        val result = runCatching {
            MultiFormatReader().apply { setHints(QR_READER_HINTS) }
                .decode(BinaryBitmap(binarizer))
        }.getOrNull()
        if (result != null) return result.text
    }
    return null
}

private val QR_READER_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to Any(),
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

private fun invertPixels(pixels: IntArray): IntArray = IntArray(pixels.size) { index ->
    val pixel = pixels[index]
    val r = 0xFF - ((pixel shr 16) and 0xFF)
    val g = 0xFF - ((pixel shr 8) and 0xFF)
    val b = 0xFF - (pixel and 0xFF)
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}