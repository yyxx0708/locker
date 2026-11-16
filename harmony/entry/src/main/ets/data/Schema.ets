/**
 * 数据库建表语句与默认数据。
 *
 * 表结构、列名与 Android 版 Room 完全一致（persons / wear_segments / events /
 * event_types / daily_photos / daily_random_numbers / sync_identities），
 * 这样两端的 JSON 备份与同步包字段可以直接互通，排查问题时也能对照同一套字段。
 */
export const DB_NAME = 'locker.db';
export const DB_VERSION = 9;

export const CREATE_TABLES: string[] = [
  `CREATE TABLE IF NOT EXISTS persons (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    createdAt INTEGER NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS wear_segments (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    personId INTEGER NOT NULL,
    startAt INTEGER NOT NULL,
    endAt INTEGER
  )`,
  `CREATE INDEX IF NOT EXISTS index_wear_segments_personId_startAt
    ON wear_segments (personId, startAt)`,
  `CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    personId INTEGER NOT NULL,
    type TEXT NOT NULL,
    occurredAt INTEGER NOT NULL,
    note TEXT
  )`,
  `CREATE INDEX IF NOT EXISTS index_events_personId_occurredAt
    ON events (personId, occurredAt)`,
  `CREATE TABLE IF NOT EXISTS event_types (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    typeKey TEXT NOT NULL,
    name TEXT NOT NULL,
    colorArgb INTEGER NOT NULL,
    sortOrder INTEGER NOT NULL,
    builtIn INTEGER NOT NULL
  )`,
  `CREATE UNIQUE INDEX IF NOT EXISTS index_event_types_typeKey
    ON event_types (typeKey)`,
  `CREATE TABLE IF NOT EXISTS daily_photos (
    personId INTEGER NOT NULL,
    epochDay INTEGER NOT NULL,
    jpeg BLOB NOT NULL,
    updatedAt INTEGER NOT NULL,
    fingerprint TEXT NOT NULL DEFAULT '',
    video BLOB,
    videoFingerprint TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (personId, epochDay)
  )`,
  `CREATE TABLE IF NOT EXISTS daily_random_numbers (
    personId INTEGER NOT NULL,
    epochDay INTEGER NOT NULL,
    number TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    PRIMARY KEY (personId, epochDay)
  )`,
  `CREATE UNIQUE INDEX IF NOT EXISTS index_daily_random_numbers_epochDay_number
    ON daily_random_numbers (epochDay, number)`,
  `CREATE TABLE IF NOT EXISTS sync_identities (
    identityId TEXT NOT NULL,
    personId INTEGER NOT NULL,
    displayName TEXT NOT NULL,
    code TEXT NOT NULL,
    codeSalt TEXT NOT NULL,
    createdAt INTEGER NOT NULL,
    lastExportedAt INTEGER,
    PRIMARY KEY (identityId)
  )`,
  `CREATE UNIQUE INDEX IF NOT EXISTS index_sync_identities_personId
    ON sync_identities (personId)`,
];

/** 只在数据库为空时插入的初始数据。 */
export const INSERT_DEFAULT_EVENT_TYPES: string[] = [
  `INSERT INTO event_types (typeKey, name, colorArgb, sortOrder, builtIn)
    VALUES ('locked', '锁射', ${0xFF2A78D6}, 0, 1)`,
  `INSERT INTO event_types (typeKey, name, colorArgb, sortOrder, builtIn)
    VALUES ('unlocked', '开锁射精', ${0xFFEB6834}, 1, 1)`,
];
