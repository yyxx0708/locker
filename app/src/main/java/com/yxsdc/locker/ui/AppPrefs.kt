package com.yxsdc.locker.ui

import android.content.Context
import com.yxsdc.locker.domain.SyncRange

/** 上传照片时的本机偏好。 */
object PhotoPreferences {
    private const val KEY_WATERMARK = "photo_watermark"
    private const val KEY_CHECK_NUMBER = "photo_check_number"

    fun watermarkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WATERMARK, false)

    fun setWatermarkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATERMARK, enabled).apply()
    }

    fun checkNumberAfterUpload(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_NUMBER, false)

    fun setCheckNumberAfterUpload(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_NUMBER, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("locker_settings", Context.MODE_PRIVATE)
}

/** 记住「发送给掌管者」上次选择的数据范围。 */
object SyncRangePreferences {
    private const val KEY_RANGE = "sync_range"

    fun read(context: Context): SyncRange {
        val stored = prefs(context).getString(KEY_RANGE, null)
        return SyncRange.entries.firstOrNull { it.name == stored } ?: SyncRange.TODAY
    }

    fun write(context: Context, range: SyncRange) {
        prefs(context).edit().putString(KEY_RANGE, range.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("locker_settings", Context.MODE_PRIVATE)
}

/** 记录每个同步身份最近一次成功应用的包，用于识别重复导入。 */
object SyncImportPreferences {
    private fun key(identityId: String) = "last_import_hash_$identityId"
    private fun prefs(context: Context) =
        context.getSharedPreferences("locker_settings", Context.MODE_PRIVATE)

    fun lastHash(context: Context, identityId: String): String? =
        prefs(context).getString(key(identityId), null)

    fun setLastHash(context: Context, identityId: String, hash: String) {
        prefs(context).edit().putString(key(identityId), hash).apply()
    }
}

/** 全屏随机数的显示偏好。 */
object FullScreenPreferences {
    private const val KEY_MIRRORED = "fullscreen_number_mirrored"

    fun isMirrored(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MIRRORED, false)

    fun setMirrored(context: Context, mirrored: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIRRORED, mirrored).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("locker_settings", Context.MODE_PRIVATE)
}
