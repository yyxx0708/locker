package com.yxsdc.locker.ui

import android.content.Context
import android.content.pm.PackageManager

/** 从包信息读取版本名，避免界面里硬编码版本号。 */
fun Context.appVersionName(): String = runCatching {
    val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    info.versionName.orEmpty()
}.getOrDefault("")
