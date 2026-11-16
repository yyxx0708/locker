package com.yxsdc.locker.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 读取相册图片，按 EXIF 方向校正，缩放到最长边不超过 [maxDimension]，压缩为 JPEG。
 * [watermarkAt] 不为 null 时，在右下角烧录该时间戳；水印绘制与压缩共用同一次解码/编码，
 * 避免"先压成 JPEG、再解压加水印、再压一次"的二次编解码。
 */
fun loadCompressedJpeg(
    context: Context,
    uri: Uri,
    maxDimension: Int = 1024,
    quality: Int = 80,
    watermarkAt: Long? = null,
): ByteArray? {
    val resolver = context.contentResolver

    // 只读一次源文件：bounds、解码与 EXIF 都基于同一份字节，避免重复打开输入流
    val sourceBytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    var bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, options)
        ?: return null

    runCatching {
        val exif = ExifInterface(ByteArrayInputStream(sourceBytes))
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return@runCatching
        }
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (transformed !== bitmap) {
            bitmap.recycle()
            bitmap = transformed
        }
    }

    val longestSide = max(bitmap.width, bitmap.height)
    if (longestSide > maxDimension) {
        val scale = maxDimension.toFloat() / longestSide
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) {
            bitmap.recycle()
            bitmap = scaled
        }
    }

    if (watermarkAt != null) {
        val marked = drawWatermark(bitmap, watermarkAt)
        if (marked != null) {
            bitmap.recycle()
            bitmap = marked
        }
    }

    return try {
        ByteArrayOutputStream().use { output ->
            val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (compressed && bytes.isNotEmpty()) bytes else null
        }
    } finally {
        bitmap.recycle()
    }
}

/**
 * 在图片右下角烧录白色描边时间戳，返回可压缩的 ARGB_8888 副本。
 * 创建副本失败时返回 null，由调用方回退到无水印原图。
 */
private fun drawWatermark(source: Bitmap, timestamp: Long): Bitmap? {
    val mutable = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
    val canvas = Canvas(mutable)
    val text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    val textSize = (mutable.width * 0.04f).coerceAtLeast(18f)
    val typeface = Typeface.DEFAULT_BOLD
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.typeface = typeface
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = textSize * 0.14f
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.typeface = typeface
        color = Color.WHITE
    }
    val margin = mutable.width * 0.03f
    val x = mutable.width - margin - fill.measureText(text)
    val y = mutable.height - margin
    canvas.drawText(text, x, y, stroke)
    canvas.drawText(text, x, y, fill)
    return mutable
}
