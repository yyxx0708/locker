package com.yxsdc.locker

import android.app.Application
import com.yxsdc.locker.data.AppDatabase
import com.yxsdc.locker.data.LockerRepository
import com.yxsdc.locker.ui.PersonPreferences
import com.yxsdc.locker.ui.theme.ThemePreferences

class LockerApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: LockerRepository by lazy { LockerRepository(database, database.lockerDao()) }
    val themePreferences: ThemePreferences by lazy { ThemePreferences(this) }
    val personPreferences: PersonPreferences by lazy { PersonPreferences(this) }

    override fun onCreate() {
        super.onCreate()
        cleanStaleCache()
    }

    /** 进程被杀时可能留下相机、同步包的临时文件；只清理一天前的，避免影响进行中的操作。 */
    private fun cleanStaleCache() {
        kotlin.concurrent.thread(name = "stale-cache-cleanup") {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            listOf(java.io.File(cacheDir, "camera"), java.io.File(cacheDir, "sync"))
                .forEach { dir ->
                    dir.listFiles()?.forEach { file ->
                        if (file.lastModified() < cutoff) file.delete()
                    }
                }
        }
    }
}
