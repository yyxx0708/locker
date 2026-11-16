package com.yxsdc.locker.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.yxsdc.locker.domain.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 日报分享卡所需的纯数据，便于在非 Compose 层绘制。 */
data class ReportCardData(
    val identityName: String,
    val epochDay: Long,
    val number: String,
    val wearMinutes: Long,
    val eventCount: Int,
    val photoLabel: String,
    val photoPassed: Boolean,
    val photoBitmap: Bitmap?,
    val qrPayload: String,
)

/**
 * 把日报渲染成一张可分享的竖图（1080×1920）：
 * 标题、姓名与日期、二维码、当日随机数、三项关键数字，以及当天照片缩略图。
 */
object ReportCard {
    const val WIDTH = 1080
    const val HEIGHT = 1920
    private const val MARGIN = 72f
    private const val INK = 0xFFFFFFFF.toInt()
    private const val MUTED_LIGHT = 0xC0FFFFFF.toInt()
    private const val MUTED = 0x8CFFFFFF.toInt()
    private const val PASS = 0xFF7FC4FF.toInt()
    private const val FAIL = 0xFFFF9A76.toInt()

    private val dayFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    private val weekFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)

    fun render(data: ReportCardData): Bitmap? = runCatching {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        drawBackground(canvas, paint)

        // 顶部标识
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 34f
        paint.color = MUTED_LIGHT
        paint.letterSpacing = 0.16f
        canvas.drawText("LOCKER", MARGIN, 124f, paint)
        paint.letterSpacing = 0f

        // 姓名与日期
        paint.textSize = 76f
        paint.color = INK
        canvas.drawText(data.identityName, MARGIN, 218f, paint)

        val date = LocalDate.ofEpochDay(data.epochDay)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 36f
        paint.color = MUTED_LIGHT
        canvas.drawText(
            "${date.year}年${date.format(dayFormatter)} · ${date.format(weekFormatter)}",
            MARGIN,
            284f,
            paint,
        )

        // 二维码面板
        val qrSize = 560
        val panelTop = 340f
        val panelHeight = qrSize + 100f
        paint.color = Color.WHITE
        canvas.drawRoundRect(
            RectF(MARGIN, panelTop, WIDTH - MARGIN, panelTop + panelHeight),
            40f,
            40f,
            paint,
        )
        val qrLeft = (WIDTH - qrSize) / 2f
        generateQrAndroidBitmap(data.qrPayload, qrSize)?.let { qr ->
            // 二维码本身带 4 模块静默区，直接贴在白色面板上即可，不要再裁圆角
            canvas.drawBitmap(qr, qrLeft, panelTop + 50f, Paint(Paint.FILTER_BITMAP_FLAG))
            qr.recycle()
        }
        paint.color = 0xFF6E6C66.toInt()
        paint.textSize = 28f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "在 Locker 中「核验二维码」即可确认真伪",
            WIDTH / 2f,
            panelTop + panelHeight - 28f,
            paint,
        )
        paint.textAlign = Paint.Align.LEFT

        // 当日随机数
        val numberTop = panelTop + panelHeight + 84f
        paint.color = MUTED
        paint.textSize = 30f
        paint.letterSpacing = 0.12f
        canvas.drawText("当日随机数", MARGIN, numberTop, paint)
        paint.letterSpacing = 0.06f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 132f
        paint.color = INK
        canvas.drawText(data.number, MARGIN - 2f, numberTop + 130f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.letterSpacing = 0f

        // 分隔线
        val dividerY = numberTop + 186f
        paint.color = 0x33FFFFFF
        canvas.drawRect(MARGIN, dividerY, WIDTH - MARGIN, dividerY + 2f, paint)

        // 左下：当天照片
        val contentTop = dividerY + 56f
        val photoSize = 380
        drawPhoto(canvas, paint, data.photoBitmap, MARGIN, contentTop, photoSize.toFloat())

        // 右下：三项关键数字
        val statsLeft = MARGIN + photoSize + 56f
        drawStat(
            canvas, paint, statsLeft, contentTop + 40f,
            "佩戴时长", formatDuration(data.wearMinutes * 60_000), INK,
        )
        drawStat(
            canvas, paint, statsLeft, contentTop + 170f,
            "事件次数", "${data.eventCount} 次", INK,
        )
        drawStat(
            canvas, paint, statsLeft, contentTop + 300f,
            "照片核验", data.photoLabel,
            if (data.photoPassed) PASS else FAIL,
        )

        // 页脚
        paint.color = MUTED
        paint.textSize = 26f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "数据仅保存于本机 · 无账号 · 无云端同步",
            WIDTH / 2f,
            HEIGHT - 64f,
            paint,
        )
        paint.textAlign = Paint.Align.LEFT

        bitmap
    }.getOrNull()

    private fun drawBackground(canvas: Canvas, paint: Paint) {
        paint.shader = LinearGradient(
            0f,
            0f,
            WIDTH.toFloat(),
            HEIGHT.toFloat(),
            intArrayOf(
                0xFF0B1220.toInt(),
                0xFF12304F.toInt(),
                0xFF1A1A19.toInt(),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null

        // 柔和的品牌色光晕，避免纯色底显得平
        paint.color = 0x4D2A78D6
        canvas.drawCircle(WIDTH - 110f, 90f, 360f, paint)
        paint.color = 0x2EEB6834
        canvas.drawCircle(60f, HEIGHT - 240f, 320f, paint)
    }

    private fun drawStat(
        canvas: Canvas,
        paint: Paint,
        left: Float,
        baseline: Float,
        label: String,
        value: String,
        valueColor: Int,
    ) {
        paint.color = MUTED
        paint.textSize = 28f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(label, left, baseline, paint)

        paint.color = valueColor
        paint.textSize = 46f
        paint.typeface = Typeface.DEFAULT_BOLD
        val maxWidth = WIDTH - MARGIN - left
        canvas.drawText(
            ellipsize(value, paint, maxWidth),
            left,
            baseline + 62f,
            paint,
        )
        paint.typeface = Typeface.DEFAULT
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }

    private fun drawPhoto(
        canvas: Canvas,
        paint: Paint,
        photo: Bitmap?,
        left: Float,
        top: Float,
        size: Float,
    ) {
        val rect = RectF(left, top, left + size, top + size)
        val radius = 32f

        if (photo == null) {
            paint.color = 0x14FFFFFF
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.color = MUTED
            paint.textSize = 28f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "今天未上传照片",
                rect.centerX(),
                rect.centerY(),
                paint,
            )
            paint.textAlign = Paint.Align.LEFT
            return
        }

        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(path)
        // 居中裁成正方形，避免变形
        val side = minOf(photo.width, photo.height)
        val srcLeft = (photo.width - side) / 2
        val srcTop = (photo.height - side) / 2
        canvas.drawBitmap(
            photo,
            Rect(srcLeft, srcTop, srcLeft + side, srcTop + side),
            rect,
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        canvas.restoreToCount(save)
    }
}