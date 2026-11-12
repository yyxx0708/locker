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
}
