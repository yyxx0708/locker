import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { CREATE_TABLES, DB_NAME, INSERT_DEFAULT_EVENT_TYPES } from './Schema';
import { DEFAULT_PERSON_NAME } from './Models';

const DOMAIN = 0x0000;

/**
 * 数据库单例。
 *
 * 与 Android 版不同，这里不用 Room 的自动迁移：鸿蒙是全新安装，
 * 没有旧版本数据库要迁移，所以建表直接按当前 schema 走，
 * 只在「首次创建」时补默认人物与内置事件类型。
 */
export class LockerStore {
  private static rdb: relationalStore.RdbStore | null = null;

  static async init(context: common.Context): Promise<relationalStore.RdbStore> {
    if (LockerStore.rdb !== null) {
      return LockerStore.rdb;
    }
    const config: relationalStore.StoreConfig = {
      name: DB_NAME,
      securityLevel: relationalStore.SecurityLevel.S3,
      encrypt: false,
    };
    const store = await relationalStore.getRdbStore(context, config);
    for (const sql of CREATE_TABLES) {
      await store.executeSql(sql);
    }
    await LockerStore.migrateDailyPhotos(store);
    await LockerStore.seedIfEmpty(store);
    LockerStore.rdb = store;
    return store;
  }

  static get(): relationalStore.RdbStore {
    const store = LockerStore.rdb;
    if (store === null) {
      throw new Error('数据库尚未初始化');
    }
    return store;
  }

  static isReady(): boolean {
    return LockerStore.rdb !== null;
  }

  static close(): void {
    LockerStore.rdb = null;
  }

  private static async migrateDailyPhotos(store: relationalStore.RdbStore): Promise<void> {
    const columns = await store.querySql('PRAGMA table_info(daily_photos)');
    const names = new Set<string>();
    while (columns.goToNextRow()) {
      names.add(columns.getString(columns.getColumnIndex('name')));
    }
    columns.close();
    if (!names.has('video')) {
      await store.executeSql('ALTER TABLE daily_photos ADD COLUMN video BLOB');
    }
    if (!names.has('videoFingerprint')) {
      await store.executeSql("ALTER TABLE daily_photos ADD COLUMN videoFingerprint TEXT NOT NULL DEFAULT ''");
    }
  }

  /** 空库时写入默认人物「我」与两个内置事件类型。 */
  private static async seedIfEmpty(store: relationalStore.RdbStore): Promise<void> {
    const persons = await store.querySql('SELECT COUNT(*) AS c FROM persons');
    let personCount = 0;
    if (persons.goToFirstRow()) {
      personCount = persons.getLong(persons.getColumnIndex('c'));
    }
    persons.close();

    const types = await store.querySql('SELECT COUNT(*) AS c FROM event_types');
    let typeCount = 0;
    if (types.goToFirstRow()) {
      typeCount = types.getLong(types.getColumnIndex('c'));
    }
    types.close();

    if (typeCount === 0) {
      for (const sql of INSERT_DEFAULT_EVENT_TYPES) {
        await store.executeSql(sql);
      }
    }
    if (personCount === 0) {
      await store.executeSql(
        'INSERT INTO persons (name, createdAt) VALUES (?, ?)',
        [DEFAULT_PERSON_NAME, Date.now()],
      );
    }
    hilog.info(DOMAIN, 'Locker', 'db seeded: persons=%{public}d types=%{public}d',
      personCount, typeCount);
  }
}
