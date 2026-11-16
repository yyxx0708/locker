import { relationalStore } from '@kit.ArkData';
import {
  DailyPhoto,
  DailyPhotoMeta,
  DailyRandomNumber,
  EventRecord,
  EventTypeEntity,
  Person,
  SyncIdentity,
  WearSegment,
} from './Models';

/**
 * 数据访问层。
 *
 * 与 Android 的 Room DAO 对应，但改成了显式 SQL：鸿蒙没有 Room 的代码生成，
 * 手写语句反而更容易核对两端字段是否一致。所有查询都在这里收口，
 * 上层（Repository / ViewModel）只处理业务规则。
 */
export class LockerDao {
  private store: relationalStore.RdbStore;

  constructor(store: relationalStore.RdbStore) {
    this.store = store;
  }

  // ---------------- 人物 ----------------

  async getPersons(): Promise<Person[]> {
    const rs = await this.store.querySql('SELECT * FROM persons ORDER BY id ASC');
    const list: Person[] = [];
    while (rs.goToNextRow()) {
      list.push({
        id: rs.getLong(rs.getColumnIndex('id')),
        name: rs.getString(rs.getColumnIndex('name')),
        createdAt: rs.getLong(rs.getColumnIndex('createdAt')),
      });
    }
    rs.close();
    return list;
  }

  async getPerson(id: number): Promise<Person | null> {
    const rs = await this.store.querySql('SELECT * FROM persons WHERE id = ? LIMIT 1', [id]);
    let person: Person | null = null;
    if (rs.goToFirstRow()) {
      person = {
        id: rs.getLong(rs.getColumnIndex('id')),
        name: rs.getString(rs.getColumnIndex('name')),
        createdAt: rs.getLong(rs.getColumnIndex('createdAt')),
      };
    }
    rs.close();
    return person;
  }

  async insertPerson(name: string, createdAt: number): Promise<number> {
    const id = await this.store.execute(
      'INSERT INTO persons (name, createdAt) VALUES (?, ?)',
      [name, createdAt],
    );
    return Number(id);
  }

  async updatePerson(person: Person): Promise<void> {
    await this.store.execute(
      'UPDATE persons SET name = ?, createdAt = ? WHERE id = ?',
      [person.name, person.createdAt, person.id],
    );
  }

  async deletePerson(id: number): Promise<void> {
    await this.store.execute('DELETE FROM persons WHERE id = ?', [id]);
  }

  async clearPersons(): Promise<void> {
    await this.store.execute('DELETE FROM persons');
  }

  // ---------------- 佩戴分段 ----------------

  async getActiveSegment(personId: number): Promise<WearSegment | null> {
    const rs = await this.store.querySql(
      'SELECT * FROM wear_segments WHERE personId = ? AND endAt IS NULL ' +
        'ORDER BY startAt DESC LIMIT 1',
      [personId],
    );
    let segment: WearSegment | null = null;
    if (rs.goToFirstRow()) {
      segment = LockerDao.readSegment(rs);
    }
    rs.close();
    return segment;
  }

  async getSegment(id: number, personId: number): Promise<WearSegment | null> {
    const rs = await this.store.querySql(
      'SELECT * FROM wear_segments WHERE id = ? AND personId = ? LIMIT 1',
      [id, personId],
    );
    let segment: WearSegment | null = null;
    if (rs.goToFirstRow()) {
      segment = LockerDao.readSegment(rs);
    }
    rs.close();
    return segment;
  }

  async getSegmentsOverlapping(
    personId: number,
    start: number,
    endExclusive: number,
  ): Promise<WearSegment[]> {
    const rs = await this.store.querySql(
      'SELECT * FROM wear_segments WHERE personId = ? ' +
        'AND startAt < ? AND (endAt IS NULL OR endAt > ?) ORDER BY startAt ASC',
      [personId, endExclusive, start],
    );
    return LockerDao.readSegments(rs);
  }

  async getSegmentsInRange(personId: number, from: number, to: number): Promise<WearSegment[]> {
    const rs = await this.store.querySql(
      'SELECT * FROM wear_segments WHERE personId = ? ' +
        'AND startAt BETWEEN ? AND ? ORDER BY startAt ASC',
      [personId, from, to],
    );
    return LockerDao.readSegments(rs);
  }

  async getAllSegments(): Promise<WearSegment[]> {
    const rs = await this.store.querySql('SELECT * FROM wear_segments ORDER BY startAt ASC');
    return LockerDao.readSegments(rs);
  }

  async countSegments(personId: number): Promise<number> {
    const rs = await this.store.querySql(
      'SELECT COUNT(*) AS c FROM wear_segments WHERE personId = ?',
      [personId],
    );
    let count = 0;
    if (rs.goToFirstRow()) {
      count = rs.getLong(rs.getColumnIndex('c'));
    }
    rs.close();
    return count;
  }

  async insertSegment(segment: WearSegment): Promise<number> {
    const id = await this.store.execute(
      'INSERT INTO wear_segments (personId, startAt, endAt) VALUES (?, ?, ?)',
      [segment.personId, segment.startAt, segment.endAt],
    );
    return Number(id);
  }

  async updateSegment(segment: WearSegment): Promise<void> {
    await this.store.execute(
      'UPDATE wear_segments SET personId = ?, startAt = ?, endAt = ? WHERE id = ?',
      [segment.personId, segment.startAt, segment.endAt, segment.id],
    );
  }

  async deleteSegment(id: number): Promise<void> {
    await this.store.execute('DELETE FROM wear_segments WHERE id = ?', [id]);
  }

  async deleteSegmentsForPerson(personId: number): Promise<void> {
    await this.store.execute('DELETE FROM wear_segments WHERE personId = ?', [personId]);
  }

  async clearSegments(): Promise<void> {
    await this.store.execute('DELETE FROM wear_segments');
  }

  // ---------------- 事件 ----------------

  async getEventsInRange(personId: number, from: number, to: number): Promise<EventRecord[]> {
    const rs = await this.store.querySql(
      'SELECT * FROM events WHERE personId = ? ' +
        'AND occurredAt >= ? AND occurredAt < ? ORDER BY occurredAt ASC',
      [personId, from, to],
    );
    return LockerDao.readEvents(rs);
  }

  async getAllEvents(): Promise<EventRecord[]> {
    const rs = await this.store.querySql('SELECT * FROM events ORDER BY occurredAt ASC');
    return LockerDao.readEvents(rs);
  }

  async countEvents(personId: number): Promise<number> {
    const rs = await this.store.querySql(
      'SELECT COUNT(*) AS c FROM events WHERE personId = ?',
      [personId],
    );
    let count = 0;
    if (rs.goToFirstRow()) {
      count = rs.getLong(rs.getColumnIndex('c'));
    }
    rs.close();
    return count;
  }

  async countEventsOfType(typeKey: string): Promise<number> {
    const rs = await this.store.querySql(
      'SELECT COUNT(*) AS c FROM events WHERE type = ?',
      [typeKey],
    );
    let count = 0;
    if (rs.goToFirstRow()) {
      count = rs.getLong(rs.getColumnIndex('c'));
    }
    rs.close();
    return count;
  }

  async insertEvent(event: EventRecord): Promise<number> {
    const id = await this.store.execute(
      'INSERT INTO events (personId, type, occurredAt, note) VALUES (?, ?, ?, ?)',
      [event.personId, event.type, event.occurredAt, event.note],
    );
    return Number(id);
  }

  async updateEvent(event: EventRecord): Promise<void> {
    await this.store.execute(
      'UPDATE events SET personId = ?, type = ?, occurredAt = ?, note = ? WHERE id = ?',
      [event.personId, event.type, event.occurredAt, event.note, event.id],
    );
  }

  async deleteEvent(id: number): Promise<void> {
    await this.store.execute('DELETE FROM events WHERE id = ?', [id]);
  }

  async deleteEventsForPerson(personId: number): Promise<void> {
    await this.store.execute('DELETE FROM events WHERE personId = ?', [personId]);
  }

  async deleteEventsOfType(typeKey: string): Promise<void> {
    await this.store.execute('DELETE FROM events WHERE type = ?', [typeKey]);
  }

  async clearEvents(): Promise<void> {
    await this.store.execute('DELETE FROM events');
  }

  // ---------------- 事件类型 ----------------

  async getEventTypes(): Promise<EventTypeEntity[]> {
    const rs = await this.store.querySql(
      'SELECT * FROM event_types ORDER BY sortOrder ASC, id ASC',
    );
    const list: EventTypeEntity[] = [];
    while (rs.goToNextRow()) {
      list.push({
        id: rs.getLong(rs.getColumnIndex('id')),
        typeKey: rs.getString(rs.getColumnIndex('typeKey')),
        name: rs.getString(rs.getColumnIndex('name')),
        colorArgb: rs.getLong(rs.getColumnIndex('colorArgb')),
        sortOrder: rs.getLong(rs.getColumnIndex('sortOrder')),
        builtIn: rs.getLong(rs.getColumnIndex('builtIn')) !== 0,
      });
    }
    rs.close();
    return list;
  }

  async insertEventType(type: EventTypeEntity): Promise<number> {
    const id = await this.store.execute(
      'INSERT INTO event_types (typeKey, name, colorArgb, sortOrder, builtIn) ' +
        'VALUES (?, ?, ?, ?, ?)',
      [type.typeKey, type.name, type.colorArgb, type.sortOrder, type.builtIn ? 1 : 0],
    );
    return Number(id);
  }

  async updateEventType(type: EventTypeEntity): Promise<void> {
    await this.store.execute(
      'UPDATE event_types SET typeKey = ?, name = ?, colorArgb = ?, sortOrder = ?, ' +
        'builtIn = ? WHERE id = ?',
      [type.typeKey, type.name, type.colorArgb, type.sortOrder, type.builtIn ? 1 : 0, type.id],
    );
  }

  async deleteEventType(id: number): Promise<void> {
    await this.store.execute('DELETE FROM event_types WHERE id = ?', [id]);
  }

  async clearEventTypes(): Promise<void> {
    await this.store.execute('DELETE FROM event_types');
  }

  // ---------------- 每日照片 ----------------

  async getDailyPhotosInRange(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyPhoto[]> {
    const rs = await this.store.querySql(
      'SELECT personId, epochDay, jpeg, updatedAt, fingerprint, videoFingerprint FROM daily_photos WHERE personId = ? AND epochDay BETWEEN ? AND ? ' +
        'ORDER BY epochDay ASC',
      [personId, startEpochDay, endEpochDay],
    );
    const list: DailyPhoto[] = [];
    while (rs.goToNextRow()) {
      list.push({
        personId: rs.getLong(rs.getColumnIndex('personId')),
        epochDay: rs.getLong(rs.getColumnIndex('epochDay')),
        jpeg: rs.getBlob(rs.getColumnIndex('jpeg')),
        video: null,
        updatedAt: rs.getLong(rs.getColumnIndex('updatedAt')),
        fingerprint: rs.getString(rs.getColumnIndex('fingerprint')),
        videoFingerprint: rs.getString(rs.getColumnIndex('videoFingerprint')),
      });
    }
    rs.close();
    return list;
  }

  async getDailyPhotoMetas(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyPhotoMeta[]> {
    const rs = await this.store.querySql(
      'SELECT epochDay, updatedAt, fingerprint, videoFingerprint, LENGTH(jpeg) AS byteSize, ' +
        'COALESCE(LENGTH(video), 0) AS videoByteSize FROM daily_photos ' +
        'WHERE personId = ? AND epochDay BETWEEN ? AND ? ORDER BY epochDay ASC',
      [personId, startEpochDay, endEpochDay],
    );
    const list: DailyPhotoMeta[] = [];
    while (rs.goToNextRow()) {
      list.push({
        epochDay: rs.getLong(rs.getColumnIndex('epochDay')),
        updatedAt: rs.getLong(rs.getColumnIndex('updatedAt')),
        fingerprint: rs.getString(rs.getColumnIndex('fingerprint')),
        byteSize: rs.getLong(rs.getColumnIndex('byteSize')),
        videoByteSize: rs.getLong(rs.getColumnIndex('videoByteSize')),
        videoFingerprint: rs.getString(rs.getColumnIndex('videoFingerprint')),
      });
    }
    rs.close();
    return list;
  }

  async getDailyPhoto(personId: number, epochDay: number): Promise<DailyPhoto | null> {
    const rs = await this.store.querySql(
      'SELECT personId, epochDay, jpeg, updatedAt, fingerprint, videoFingerprint FROM daily_photos WHERE personId = ? AND epochDay = ? LIMIT 1',
      [personId, epochDay],
    );
    let photo: DailyPhoto | null = null;
    if (rs.goToFirstRow()) {
      photo = {
        personId: rs.getLong(rs.getColumnIndex('personId')),
        epochDay: rs.getLong(rs.getColumnIndex('epochDay')),
        jpeg: rs.getBlob(rs.getColumnIndex('jpeg')),
        video: null,
        updatedAt: rs.getLong(rs.getColumnIndex('updatedAt')),
        fingerprint: rs.getString(rs.getColumnIndex('fingerprint')),
        videoFingerprint: rs.getString(rs.getColumnIndex('videoFingerprint')),
      };
    }
    rs.close();
    return photo;
  }

  async getDailyPhotoVideo(personId: number, epochDay: number): Promise<Uint8Array | null> {
    const sizeResult = await this.store.querySql(
      'SELECT LENGTH(video) AS byteSize FROM daily_photos WHERE personId = ? AND epochDay = ? LIMIT 1',
      [personId, epochDay],
    );
    let byteSize = 0;
    try {
      if (!sizeResult.goToFirstRow() || sizeResult.isColumnNull(0)) {
        return null;
      }
      byteSize = sizeResult.getLong(0);
    } finally {
      sizeResult.close();
    }
    if (byteSize <= 0 || byteSize > 60 * 1024 * 1024) {
      throw new Error('动态视频体积无效');
    }
    const video = new Uint8Array(byteSize);
    const chunkSize = 512 * 1024;
    for (let offset = 0; offset < byteSize; offset += chunkSize) {
      const rs = await this.store.querySql(
        'SELECT SUBSTR(video, ?, ?) AS chunk FROM daily_photos WHERE personId = ? AND epochDay = ? LIMIT 1',
        [offset + 1, Math.min(chunkSize, byteSize - offset), personId, epochDay],
      );
      try {
        if (!rs.goToFirstRow()) {
          throw new Error('动态视频读取不完整');
        }
        const chunk = rs.getBlob(0);
        if (chunk.length !== Math.min(chunkSize, byteSize - offset)) {
          throw new Error('动态视频读取不完整');
        }
        video.set(chunk, offset);
      } finally {
        rs.close();
      }
    }
    return video;
  }

  async updateDailyPhotoFingerprint(personId: number, epochDay: number, fingerprint: string): Promise<void> {
    await this.store.execute('UPDATE daily_photos SET fingerprint = ? WHERE personId = ? AND epochDay = ?',
      [fingerprint, personId, epochDay]);
  }

  /** 只判断当天是否有照片，不读取 JPEG 字节。 */
  async hasDailyPhoto(personId: number, epochDay: number): Promise<boolean> {
    const rs = await this.store.querySql(
      'SELECT EXISTS(SELECT 1 FROM daily_photos WHERE personId = ? AND epochDay = ?) AS e',
      [personId, epochDay],
    );
    let exists = false;
    if (rs.goToFirstRow()) {
      exists = rs.getLong(rs.getColumnIndex('e')) !== 0;
    }
    rs.close();
    return exists;
  }

  async upsertDailyPhoto(photo: DailyPhoto): Promise<void> {
    await this.store.execute(
      'INSERT OR REPLACE INTO daily_photos (personId, epochDay, jpeg, updatedAt, fingerprint, video, videoFingerprint) ' +
        'VALUES (?, ?, ?, ?, ?, ?, ?)',
      [photo.personId, photo.epochDay, photo.jpeg, photo.updatedAt, photo.fingerprint,
        photo.video, photo.videoFingerprint],
    );
  }

  async deleteDailyPhoto(personId: number, epochDay: number): Promise<void> {
    await this.store.execute(
      'DELETE FROM daily_photos WHERE personId = ? AND epochDay = ?',
      [personId, epochDay],
    );
  }

  async deleteDailyPhotosForPerson(personId: number): Promise<void> {
    await this.store.execute('DELETE FROM daily_photos WHERE personId = ?', [personId]);
  }

  async clearDailyPhotos(): Promise<void> {
    await this.store.execute('DELETE FROM daily_photos');
  }

  // ---------------- 每日随机数 ----------------

  async getDailyRandomNumber(
    personId: number,
    epochDay: number,
  ): Promise<DailyRandomNumber | null> {
    const rs = await this.store.querySql(
      'SELECT * FROM daily_random_numbers WHERE personId = ? AND epochDay = ? LIMIT 1',
      [personId, epochDay],
    );
    let entry: DailyRandomNumber | null = null;
    if (rs.goToFirstRow()) {
      entry = LockerDao.readRandomNumber(rs);
    }
    rs.close();
    return entry;
  }

  async getRandomNumbersForDay(epochDay: number): Promise<string[]> {
    const rs = await this.store.querySql(
      'SELECT number FROM daily_random_numbers WHERE epochDay = ?',
      [epochDay],
    );
    const list: string[] = [];
    while (rs.goToNextRow()) {
      list.push(rs.getString(rs.getColumnIndex('number')));
    }
    rs.close();
    return list;
  }

  async getAllDailyRandomNumbers(): Promise<DailyRandomNumber[]> {
    const rs = await this.store.querySql(
      'SELECT * FROM daily_random_numbers ORDER BY epochDay ASC, personId ASC',
    );
    const list: DailyRandomNumber[] = [];
    while (rs.goToNextRow()) {
      list.push(LockerDao.readRandomNumber(rs));
    }
    rs.close();
    return list;
  }

  async getDailyRandomNumbersInRange(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyRandomNumber[]> {
    const rs = await this.store.querySql(
      'SELECT * FROM daily_random_numbers WHERE personId = ? AND epochDay BETWEEN ? AND ? ' +
        'ORDER BY epochDay ASC',
      [personId, startEpochDay, endEpochDay],
    );
    const list: DailyRandomNumber[] = [];
    while (rs.goToNextRow()) {
      list.push(LockerDao.readRandomNumber(rs));
    }
    rs.close();
    return list;
  }

  async findNumberConflict(
    epochDay: number,
    number: string,
    personId: number,
  ): Promise<DailyRandomNumber | null> {
    const rs = await this.store.querySql(
      'SELECT * FROM daily_random_numbers WHERE epochDay = ? AND number = ? ' +
        'AND personId != ? LIMIT 1',
      [epochDay, number, personId],
    );
    let entry: DailyRandomNumber | null = null;
    if (rs.goToFirstRow()) {
      entry = LockerDao.readRandomNumber(rs);
    }
    rs.close();
    return entry;
  }

  /** 返回是否插入成功；号码与当天其他人重复时返回 false。 */
  /**
   * 写入每日随机数。返回是否成功写入本条。
   * 直接用 INSERT：号码被当天其他人占用、或该人物当天已有记录时，
   * 唯一索引会抛错，捕获后返回 false，由调用方换号重试。
   */
  async insertDailyRandomNumber(entry: DailyRandomNumber): Promise<boolean> {
    try {
      await this.store.execute(
        'INSERT INTO daily_random_numbers (personId, epochDay, number, createdAt) ' +
          'VALUES (?, ?, ?, ?)',
        [entry.personId, entry.epochDay, entry.number, entry.createdAt],
      );
      return true;
    } catch (err) {
      return false;
    }
  }

  async updateDailyRandomNumber(entry: DailyRandomNumber): Promise<void> {
    await this.store.execute(
      'UPDATE daily_random_numbers SET number = ?, createdAt = ? ' +
        'WHERE personId = ? AND epochDay = ?',
      [entry.number, entry.createdAt, entry.personId, entry.epochDay],
    );
  }

  async deleteDailyRandomNumber(personId: number, epochDay: number): Promise<void> {
    await this.store.execute(
      'DELETE FROM daily_random_numbers WHERE personId = ? AND epochDay = ?',
      [personId, epochDay],
    );
  }

  async deleteDailyRandomNumbersForPerson(personId: number): Promise<void> {
    await this.store.execute('DELETE FROM daily_random_numbers WHERE personId = ?', [personId]);
  }

  async clearDailyRandomNumbers(): Promise<void> {
    await this.store.execute('DELETE FROM daily_random_numbers');
  }

  // ---------------- 同步身份 ----------------

  async getSyncIdentityForPerson(personId: number): Promise<SyncIdentity | null> {
    const rs = await this.store.querySql(
      'SELECT * FROM sync_identities WHERE personId = ? LIMIT 1',
      [personId],
    );
    let identity: SyncIdentity | null = null;
    if (rs.goToFirstRow()) {
      identity = LockerDao.readIdentity(rs);
    }
    rs.close();
    return identity;
  }

  async getSyncIdentity(identityId: string): Promise<SyncIdentity | null> {
    const rs = await this.store.querySql(
      'SELECT * FROM sync_identities WHERE identityId = ? LIMIT 1',
      [identityId],
    );
    let identity: SyncIdentity | null = null;
    if (rs.goToFirstRow()) {
      identity = LockerDao.readIdentity(rs);
    }
    rs.close();
    return identity;
  }

  async getAllSyncIdentities(): Promise<SyncIdentity[]> {
    const rs = await this.store.querySql('SELECT * FROM sync_identities');
    const list: SyncIdentity[] = [];
    while (rs.goToNextRow()) {
      list.push(LockerDao.readIdentity(rs));
    }
    rs.close();
    return list;
  }

  async upsertSyncIdentity(identity: SyncIdentity): Promise<void> {
    await this.store.execute(
      'INSERT OR REPLACE INTO sync_identities ' +
        '(identityId, personId, displayName, code, codeSalt, createdAt, lastExportedAt) ' +
        'VALUES (?, ?, ?, ?, ?, ?, ?)',
      [
        identity.identityId,
        identity.personId,
        identity.displayName,
        identity.code,
        identity.codeSalt,
        identity.createdAt,
        identity.lastExportedAt,
      ],
    );
  }

  async deleteSyncIdentityForPerson(personId: number): Promise<void> {
    await this.store.execute('DELETE FROM sync_identities WHERE personId = ?', [personId]);
  }

  async clearSyncIdentities(): Promise<void> {
    await this.store.execute('DELETE FROM sync_identities');
  }

  // ---------------- 事务 ----------------

  /**
   * 在单个事务里执行。回调抛错会整体回滚，
   * 与 Android 的 withTransaction 语义一致。
   */
  async transaction<T>(action: () => Promise<T>): Promise<T> {
    this.store.beginTransaction();
    try {
      const result = await action();
      this.store.commit();
      return result;
    } catch (err) {
      try {
        this.store.rollBack();
      } catch (rollbackErr) {
        // 回滚失败时保留原始异常，避免掩盖真正的错误
      }
      const error: Error = err instanceof Error ? err : new Error(`${err}`);
      throw error;
    }
  }

  // ---------------- 结果集读取 ----------------

  private static readSegment(rs: relationalStore.ResultSet): WearSegment {
    const endIndex = rs.getColumnIndex('endAt');
    return {
      id: rs.getLong(rs.getColumnIndex('id')),
      personId: rs.getLong(rs.getColumnIndex('personId')),
      startAt: rs.getLong(rs.getColumnIndex('startAt')),
      endAt: rs.isColumnNull(endIndex) ? null : rs.getLong(endIndex),
    };
  }

  private static readSegments(rs: relationalStore.ResultSet): WearSegment[] {
    const list: WearSegment[] = [];
    while (rs.goToNextRow()) {
      list.push(LockerDao.readSegment(rs));
    }
    rs.close();
    return list;
  }

  private static readEvents(rs: relationalStore.ResultSet): EventRecord[] {
    const list: EventRecord[] = [];
    const noteIndex = rs.getColumnIndex('note');
    while (rs.goToNextRow()) {
      list.push({
        id: rs.getLong(rs.getColumnIndex('id')),
        personId: rs.getLong(rs.getColumnIndex('personId')),
        type: rs.getString(rs.getColumnIndex('type')),
        occurredAt: rs.getLong(rs.getColumnIndex('occurredAt')),
        note: rs.isColumnNull(noteIndex) ? null : rs.getString(noteIndex),
      });
    }
    rs.close();
    return list;
  }

  private static readRandomNumber(rs: relationalStore.ResultSet): DailyRandomNumber {
    return {
      personId: rs.getLong(rs.getColumnIndex('personId')),
      epochDay: rs.getLong(rs.getColumnIndex('epochDay')),
      number: rs.getString(rs.getColumnIndex('number')),
      createdAt: rs.getLong(rs.getColumnIndex('createdAt')),
    };
  }

  private static readIdentity(rs: relationalStore.ResultSet): SyncIdentity {
    const lastIndex = rs.getColumnIndex('lastExportedAt');
    return {
      identityId: rs.getString(rs.getColumnIndex('identityId')),
      personId: rs.getLong(rs.getColumnIndex('personId')),
      displayName: rs.getString(rs.getColumnIndex('displayName')),
      code: rs.getString(rs.getColumnIndex('code')),
      codeSalt: rs.getString(rs.getColumnIndex('codeSalt')),
      createdAt: rs.getLong(rs.getColumnIndex('createdAt')),
      lastExportedAt: rs.isColumnNull(lastIndex) ? null : rs.getLong(lastIndex),
    };
  }
}
