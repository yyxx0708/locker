package com.yxsdc.locker.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** 通过系统分享面板发送同步包。 */
fun shareSyncPackage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "发送给掌管者"))
}

/** 把日报分享卡写到缓存目录，返回可分享的文件。I/O 操作，需在后台线程调用。 */
fun writeReportCard(
    context: Context,
    bitmap: android.graphics.Bitmap,
    epochDay: Long,
): File {
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    // 只保留最近两张，避免缓存无限增长
    dir.listFiles()
        ?.sortedByDescending { it.lastModified() }
        ?.drop(2)
        ?.forEach { it.delete() }
    // 质量 92 的 JPEG 在 1080×1920 下约 300KB，比 PNG 小一个数量级且看不出差别
    val file = File(dir, "locker-report-$epochDay.jpg")
    file.outputStream().buffered().use { out ->
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
    }
    return file
}

/** 打开系统分享面板发送日报卡。 */
fun shareReportCard(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享日报"))
}

/** 带百分比的任务标签，例如「正在打包 45%」。 */
internal fun syncBusyLabel(base: String, progress: Int?): String =
    if (progress == null) "$base…" else "$base $progress%"

/** 人类可读的体积，例如「1.2 MB」。 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
