package com.yxsdc.locker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Person::class,
        WearSegment::class,
        EventRecord::class,
        EventTypeEntity::class,
        DailyPhoto::class,
        DailyRandomNumber::class,
        SyncIdentity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockerDao(): LockerDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `persons` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `persons` (`id`, `name`, `createdAt`) VALUES (1, ?, ?)",
                    arrayOf(DEFAULT_PERSON_NAME, System.currentTimeMillis()),
                )

                db.execSQL(
                    "CREATE TABLE `wear_segments_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`personId` INTEGER NOT NULL, " +
                        "`startAt` INTEGER NOT NULL, " +
                        "`endAt` INTEGER)"
                )
                db.execSQL(
                    "INSERT INTO `wear_segments_new` (`id`, `personId`, `startAt`, `endAt`) " +
                        "SELECT `id`, 1, `startAt`, `endAt` FROM `wear_segments`"
                )
                db.execSQL("DROP TABLE `wear_segments`")
                db.execSQL("ALTER TABLE `wear_segments_new` RENAME TO `wear_segments`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wear_segments_personId` " +
                        "ON `wear_segments` (`personId`)"
                )

                db.execSQL(
                    "CREATE TABLE `events_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`personId` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`note` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `events_new` (`id`, `personId`, `type`, `occurredAt`, `note`) " +
                        "SELECT `id`, 1, `type`, `occurredAt`, `note` FROM `events`"
                )
                db.execSQL("DROP TABLE `events`")
                db.execSQL("ALTER TABLE `events_new` RENAME TO `events`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_events_personId` ON `events` (`personId`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `event_types` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`typeKey` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`colorArgb` INTEGER NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, " +
                        "`builtIn` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_event_types_typeKey` " +
                        "ON `event_types` (`typeKey`)"
                )
                insertDefaultEventTypes(db)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_photos` (" +
                        "`personId` INTEGER NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, " +
                        "`jpeg` BLOB NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`personId`, `epochDay`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_daily_photos_personId` " +
                        "ON `daily_photos` (`personId`)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_random_numbers` (" +
                        "`personId` INTEGER NOT NULL, " +
                        "`epochDay` INTEGER NOT NULL, " +
                        "`number` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`personId`, `epochDay`))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_daily_random_numbers_epochDay_number` " +
                        "ON `daily_random_numbers` (`epochDay`, `number`)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_identities` (" +
                        "`identityId` TEXT NOT NULL, " +
                        "`personId` INTEGER NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`code` TEXT NOT NULL, " +
                        "`codeSalt` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastExportedAt` INTEGER, " +
                        "PRIMARY KEY(`identityId`))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_identities_personId` " +
                        "ON `sync_identities` (`personId`)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_wear_segments_personId_startAt` " +
                        "ON `wear_segments` (`personId`, `startAt`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_wear_segments_personId`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_events_personId_occurredAt` " +
                        "ON `events` (`personId`, `occurredAt`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_events_personId`")
                db.execSQL("DROP INDEX IF EXISTS `index_daily_photos_personId`")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `daily_photos` " +
                        "ADD COLUMN `fingerprint` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        private val CREATE_DEFAULT_DATA = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL(
                    "INSERT INTO `persons` (`name`, `createdAt`) VALUES (?, ?)",
                    arrayOf(DEFAULT_PERSON_NAME, System.currentTimeMillis()),
                )
                insertDefaultEventTypes(db)
            }
        }

        private fun insertDefaultEventTypes(db: SupportSQLiteDatabase) {
            DefaultEventTypes.builtIns().forEach { type ->
                db.execSQL(
                    "INSERT INTO `event_types` " +
                        "(`typeKey`, `name`, `colorArgb`, `sortOrder`, `builtIn`) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    arrayOf(
                        type.typeKey,
                        type.name,
                        type.colorArgb,
                        type.sortOrder,
                        if (type.builtIn) 1 else 0,
                    ),
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "locker.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                    )
                    .addCallback(CREATE_DEFAULT_DATA)
                    .build()
                    .also { instance = it }
            }
    }
}
