import {
  DailyPhoto,
  DailyPhotoMeta,
  DailyRandomNumber,
  DEFAULT_PERSON_NAME,
  EventRecord,
  EventTypeEntity,
  Person,
  SyncIdentity,
  WearSegment,
} from './Models';
import { LockerDao } from './LockerDao';
import { DailyReport } from './DailyReport';
import { SyncCrypto } from './SyncCrypto';
import { SyncPhotoRef } from './SyncPackage';
import { generateFiveDigitNumber, randomUuid } from '../common/SecureRandom';
import { todayEpochDay, startOfDayMillis, endOfDayMillis } from '../common/Dates';

const OPAQUE_ALPHA = 0xFF000000;

/** 同步导出时收集到的数据（未打包）。 */
export interface SyncExportData {
  identity: SyncIdentity;
  person: Person;
  eventTypes: EventTypeEntity[];
  segments: WearSegment[];
  events: EventRecord[];
  randomNumbers: DailyRandomNumber[];
  photos: SyncPhotoRef[];
}



/** 本地窗口数据切片，用于导入差异比对。 */
export interface SyncLocalSlice {
  segments: WearSegment[];
  events: EventRecord[];
  photos: SyncPhotoRef[];
  numbers: DailyRandomNumber[];
}

/** 发送前预检结果：窗口内的照片数量与体积。 */
export interface SyncExportEstimate {
  photoCount: number;
  photoBytes: number;
}

/**
 * 业务仓储。
 *
 * 与 Android 版 LockerRepository 一一对应：校验规则、事务边界、错误文案都保持一致，
 * 这样同一份数据在两端操作的结果相同。
 */
export class LockerRepository {
  private dao: LockerDao;

  constructor(dao: LockerDao) {
    this.dao = dao;
  }

  // ---------------- 人物 ----------------

  async getPersons(): Promise<Person[]> {
    return this.dao.getPersons();
  }

  async getPersonById(id: number): Promise<Person | null> {
    return this.dao.getPerson(id);
  }

  async addPerson(name: string): Promise<number> {
    const trimmed = LockerRepository.normalizePersonName(name);
    return this.dao.transaction(async () => {
      const persons = await this.dao.getPersons();
      if (persons.some((p: Person) => p.name === trimmed)) {
        throw new Error(`已存在同名人物：${trimmed}`);
      }
      return this.dao.insertPerson(trimmed, Date.now());
    });
  }

  async renamePerson(id: number, name: string): Promise<void> {
    const trimmed = LockerRepository.normalizePersonName(name);
    await this.dao.transaction(async () => {
      const persons = await this.dao.getPersons();
      const person = persons.find((p: Person) => p.id === id);
      if (person === undefined) {
        throw new Error('人物不存在');
      }
      if (persons.some((p: Person) => p.id !== id && p.name === trimmed)) {
        throw new Error(`已存在同名人物：${trimmed}`);
      }
      await this.dao.updatePerson(copyPerson(person, trimmed, person.createdAt));
    });
  }

  async deletePerson(person: Person): Promise<void> {
    await this.dao.transaction(async () => {
      const persons = await this.dao.getPersons();
      if (persons.length <= 1) {
        throw new Error('至少保留一个人物');
      }
      await this.dao.deleteSegmentsForPerson(person.id);
      await this.dao.deleteEventsForPerson(person.id);
      await this.dao.deleteDailyPhotosForPerson(person.id);
      await this.dao.deleteDailyRandomNumbersForPerson(person.id);
      await this.dao.deleteSyncIdentityForPerson(person.id);
      await this.dao.deletePerson(person.id);
    });
  }

  // ---------------- 事件类型 ----------------

  async getEventTypes(): Promise<EventTypeEntity[]> {
    return this.dao.getEventTypes();
  }

  async addEventType(name: string, colorArgb: number): Promise<number> {
    const trimmed = LockerRepository.normalizeEventTypeName(name);
    return this.dao.transaction(async () => {
      const types = await this.dao.getEventTypes();
      if (types.some((t: EventTypeEntity) => t.name === trimmed)) {
        throw new Error(`已存在同名事件类型：${trimmed}`);
      }
      let nextOrder = 0;
      for (const type of types) {
        nextOrder = Math.max(nextOrder, type.sortOrder + 1);
      }
      const suffix = SyncCrypto.toHex(
        SyncCrypto.sha256(new Uint8Array([Date.now() & 0xFF, (Date.now() >> 8) & 0xFF])),
      ).substring(0, 32);
      return this.dao.insertEventType({
        id: 0,
        typeKey: `custom_${suffix}`,
        name: trimmed,
        colorArgb: (colorArgb | OPAQUE_ALPHA) >>> 0,
        sortOrder: nextOrder,
        builtIn: false,
      });
    });
  }

  async updateEventType(typeKey: string, name: string, colorArgb: number): Promise<void> {
    const trimmed = LockerRepository.normalizeEventTypeName(name);
    await this.dao.transaction(async () => {
      const types = await this.dao.getEventTypes();
      const type = types.find((t: EventTypeEntity) => t.typeKey === typeKey);
      if (type === undefined) {
        throw new Error('事件类型不存在');
      }
      if (types.some((t: EventTypeEntity) => t.typeKey !== typeKey && t.name === trimmed)) {
        throw new Error(`已存在同名事件类型：${trimmed}`);
      }
      await this.dao.updateEventType({
        id: type.id,
        typeKey: type.typeKey,
        name: trimmed,
        colorArgb: (colorArgb | OPAQUE_ALPHA) >>> 0,
        sortOrder: type.sortOrder,
        builtIn: type.builtIn,
      });
    });
  }

  async deleteEventType(type: EventTypeEntity): Promise<void> {
    await this.dao.transaction(async () => {
      const types = await this.dao.getEventTypes();
      const current = types.find((t: EventTypeEntity) => t.typeKey === type.typeKey);
      if (current === undefined) {
        throw new Error('事件类型不存在');
      }
      if (current.builtIn) {
        throw new Error('内置事件类型不能删除');
      }
      await this.dao.deleteEventsOfType(current.typeKey);
      await this.dao.deleteEventType(current.id);
    });
  }

  async countEventsOfType(typeKey: string): Promise<number> {
    return this.dao.countEventsOfType(typeKey);
  }

  // ---------------- 佩戴分段 ----------------

  async getActiveSegment(personId: number): Promise<WearSegment | null> {
    return this.dao.getActiveSegment(personId);
  }

  async countSegments(personId: number): Promise<number> {
    return this.dao.countSegments(personId);
  }

  async countEvents(personId: number): Promise<number> {
    return this.dao.countEvents(personId);
  }

  async getSegmentsOverlapping(
    personId: number,
    start: number,
    endExclusive: number,
  ): Promise<WearSegment[]> {
    return this.dao.getSegmentsOverlapping(personId, start, endExclusive);
  }

  async getEventsInRange(personId: number, from: number, to: number): Promise<EventRecord[]> {
    return this.dao.getEventsInRange(personId, from, to);
  }

  async startWearing(personId: number, now: number): Promise<void> {
    await this.dao.transaction(async () => {
      await this.validateSegment(personId, null, now, null);
      await this.dao.insertSegment({ id: 0, personId, startAt: now, endAt: null });
    });
  }

  async stopWearing(personId: number, now: number): Promise<void> {
    await this.dao.transaction(async () => {
      const active = await this.dao.getActiveSegment(personId);
      if (active === null) {
        throw new Error('当前人物未佩戴');
      }
      if (now < active.startAt) {
        throw new Error('取下时间不能早于戴上时间');
      }
      await this.dao.updateSegment({
        id: active.id,
        personId: active.personId,
        startAt: active.startAt,
        endAt: now,
      });
    });
  }

  async addManualSegment(personId: number, startAt: number, endAt: number | null): Promise<void> {
    await this.dao.transaction(async () => {
      await this.validateSegment(personId, null, startAt, endAt);
      await this.dao.insertSegment({ id: 0, personId, startAt, endAt });
    });
  }

  async updateSegment(
    id: number,
    personId: number,
    startAt: number,
    endAt: number | null,
  ): Promise<void> {
    await this.dao.transaction(async () => {
      const existing = await this.dao.getSegment(id, personId);
      if (existing === null) {
        throw new Error('分段不存在');
      }
      await this.validateSegment(personId, id, startAt, endAt);
      await this.dao.updateSegment({
        id: existing.id,
        personId: existing.personId,
        startAt,
        endAt,
      });
    });
  }

  async deleteSegment(segment: WearSegment): Promise<void> {
    await this.dao.deleteSegment(segment.id);
  }

  /**
   * 校验规则：结束时间不早于开始时间；同一人物的分段之间不能重叠。
   * 只查询与目标区间相交的分段，不必每次锁上/开锁都读全部分段。
   */
  private async validateSegment(
    personId: number,
    id: number | null,
    startAt: number,
    endAt: number | null,
  ): Promise<void> {
    if (endAt !== null && endAt < startAt) {
      throw new Error('取下时间不能早于戴上时间');
    }
    const overlapping = await this.dao.getSegmentsOverlapping(
      personId,
      startAt,
      endAt === null ? Number.MAX_SAFE_INTEGER : endAt,
    );
    if (overlapping.some((s: WearSegment) => s.id !== id)) {
      throw new Error('同一人物的分段之间不能重叠');
    }
  }

  // ---------------- 事件 ----------------

  async addEvent(
    personId: number,
    typeKey: string,
    occurredAt: number,
    note: string | null,
  ): Promise<number> {
    return this.dao.transaction(async () => {
      await this.requireEventType(typeKey);
      return this.dao.insertEvent({
        id: 0,
        personId,
        type: typeKey,
        occurredAt,
        note: note !== null && note.trim().length > 0 ? note : null,
      });
    });
  }

  async updateEvent(
    id: number,
    personId: number,
    typeKey: string,
    occurredAt: number,
    note: string | null,
  ): Promise<void> {
    await this.dao.transaction(async () => {
      await this.requireEventType(typeKey);
      await this.dao.updateEvent({
        id,
        personId,
        type: typeKey,
        occurredAt,
        note: note !== null && note.trim().length > 0 ? note : null,
      });
    });
  }

  async deleteEvent(id: number): Promise<void> {
    await this.dao.deleteEvent(id);
  }

  private async requireEventType(typeKey: string): Promise<void> {
    const types = await this.dao.getEventTypes();
    if (!types.some((t: EventTypeEntity) => t.typeKey === typeKey)) {
      throw new Error('事件类型不存在');
    }
  }

  // ---------------- 每日照片 ----------------

  async getDailyPhotosInRange(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyPhoto[]> {
    return this.dao.getDailyPhotosInRange(personId, startEpochDay, endEpochDay);
  }

  async getDailyPhoto(personId: number, epochDay: number): Promise<DailyPhoto | null> {
    return this.dao.getDailyPhoto(personId, epochDay);
  }

  async hasDailyPhoto(personId: number, epochDay: number): Promise<boolean> {
    return this.dao.hasDailyPhoto(personId, epochDay);
  }

  async setDailyPhoto(personId: number, epochDay: number, jpeg: Uint8Array,
    video: Uint8Array | null = null): Promise<void> {
    if (epochDay > todayEpochDay()) {
      throw new Error('不能上传未来日期的照片');
    }
    await this.dao.upsertDailyPhoto({
      personId,
      epochDay,
      jpeg,
      video,
      updatedAt: Date.now(),
      fingerprint: DailyReport.photoFingerprint(jpeg),
      videoFingerprint: video === null ? '' : DailyReport.photoFingerprint(video),
    });
  }

  async deleteDailyPhoto(personId: number, epochDay: number): Promise<void> {
    await this.dao.deleteDailyPhoto(personId, epochDay);
  }

  // ---------------- 每日随机数 ----------------

  async getDailyRandomNumber(
    personId: number,
    epochDay: number,
  ): Promise<DailyRandomNumber | null> {
    return this.dao.getDailyRandomNumber(personId, epochDay);
  }

  async getDailyRandomNumbersInRange(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyRandomNumber[]> {
    return this.dao.getDailyRandomNumbersInRange(personId, startEpochDay, endEpochDay);
  }

  /**
   * 为「人物 + 日期」确保一个 5 位随机数。
   * 当天已有则直接返回；否则在当天未占用的号码里重试 20 次。
   * 并发下若唯一索引冲突，读回已写入的那条，保证同一人物同一天只生成一次。
   */
  async ensureDailyRandomNumber(personId: number, epochDay: number): Promise<string> {
    const existing = await this.dao.getDailyRandomNumber(personId, epochDay);
    if (existing !== null) {
      return existing.number;
    }
    for (let attempt = 0; attempt < 20; attempt++) {
      const candidate = LockerRepository.generateFiveDigitNumber();
      const inserted = await this.dao.insertDailyRandomNumber({
        personId,
        epochDay,
        number: candidate,
        createdAt: Date.now(),
      });
      if (inserted) {
        return candidate;
      }
      // 失败可能是号码被占用，也可能是并发下别人已为本人物写入；后者直接采用
      const own = await this.dao.getDailyRandomNumber(personId, epochDay);
      if (own !== null) {
        return own.number;
      }
    }
    throw new Error('无法生成不重复的随机数');
  }

  // ---------------- 备份 ----------------

  async exportAll(): Promise<BackupData> {
    return this.dao.transaction(async (): Promise<BackupData> => {
      const persons = await this.dao.getPersons();
      const eventTypes = await this.dao.getEventTypes();
      const segments = await this.dao.getAllSegments();
      const events = await this.dao.getAllEvents();
      const dailyRandomNumbers = await this.dao.getAllDailyRandomNumbers();
      const data: BackupData = { persons, eventTypes, segments, events, dailyRandomNumbers };
      return data;
    });
  }

  /** 整体替换现有数据。照片与同步身份会被清空，与 Android 版一致。 */
  async importAll(data: BackupData): Promise<void> {
    await this.dao.transaction(async () => {
      await this.dao.clearEvents();
      await this.dao.clearSegments();
      await this.dao.clearDailyPhotos();
      await this.dao.clearDailyRandomNumbers();
      await this.dao.clearSyncIdentities();
      await this.dao.clearPersons();
      await this.dao.clearEventTypes();

      for (const type of data.eventTypes) {
        await this.dao.insertEventType({
          id: 0,
          typeKey: type.typeKey,
          name: type.name,
          colorArgb: type.colorArgb,
          sortOrder: type.sortOrder,
          builtIn: type.builtIn,
        });
      }

      const idMap = new Map<number, number>();
      for (const person of data.persons) {
        const newId = await this.dao.insertPerson(person.name.trim(), person.createdAt);
        idMap.set(person.id, newId);
      }

      for (const segment of data.segments) {
        const mapped = idMap.get(segment.personId);
        if (mapped === undefined) {
          throw new Error('备份中有分段引用了不存在的人物');
        }
        await this.dao.insertSegment({
          id: 0,
          personId: mapped,
          startAt: segment.startAt,
          endAt: segment.endAt,
        });
      }

      for (const event of data.events) {
        const mapped = idMap.get(event.personId);
        if (mapped === undefined) {
          throw new Error('备份中有事件引用了不存在的人物');
        }
        await this.dao.insertEvent({
          id: 0,
          personId: mapped,
          type: event.type,
          occurredAt: event.occurredAt,
          note: event.note,
        });
      }

      for (const entry of data.dailyRandomNumbers) {
        const mapped = idMap.get(entry.personId);
        if (mapped === undefined) {
          throw new Error('备份中有每日随机数引用了不存在的人物');
        }
        await this.dao.insertDailyRandomNumber({
          personId: mapped,
          epochDay: entry.epochDay,
          number: entry.number,
          createdAt: entry.createdAt,
        });
      }
    });
  }

  // ---------------- 同步身份 ----------------

  async getSyncIdentityForPerson(personId: number): Promise<SyncIdentity | null> {
    return this.dao.getSyncIdentityForPerson(personId);
  }

  async getSyncIdentity(identityId: string): Promise<SyncIdentity | null> {
    return this.dao.getSyncIdentity(identityId);
  }

  async getAllSyncIdentities(): Promise<SyncIdentity[]> {
    return this.dao.getAllSyncIdentities();
  }

  async ensureSyncIdentity(person: Person): Promise<SyncIdentity> {
    const existing = await this.dao.getSyncIdentityForPerson(person.id);
    if (existing !== null) {
      return existing;
    }
    const identity: SyncIdentity = {
      identityId: LockerRepository.uuid(),
      personId: person.id,
      displayName: person.name,
      code: SyncCrypto.generateCode(),
      codeSalt: SyncCrypto.newSalt(),
      createdAt: Date.now(),
      lastExportedAt: null,
    };
    await this.dao.upsertSyncIdentity(identity);
    return identity;
  }

  async regenerateSyncCode(identity: SyncIdentity): Promise<SyncIdentity> {
    const updated: SyncIdentity = {
      identityId: identity.identityId,
      personId: identity.personId,
      displayName: identity.displayName,
      code: SyncCrypto.generateCode(),
      codeSalt: SyncCrypto.newSalt(),
      createdAt: identity.createdAt,
      lastExportedAt: identity.lastExportedAt,
    };
    await this.dao.upsertSyncIdentity(updated);
    return updated;
  }

  async markIdentityExported(identityId: string, at: number): Promise<void> {
    const identity = await this.dao.getSyncIdentity(identityId);
    if (identity === null) {
      return;
    }
    await this.dao.upsertSyncIdentity({
      identityId: identity.identityId,
      personId: identity.personId,
      displayName: identity.displayName,
      code: identity.code,
      codeSalt: identity.codeSalt,
      createdAt: identity.createdAt,
      lastExportedAt: at,
    });
  }

  async createPersonForImport(name: string): Promise<number> {
    return this.dao.insertPerson(LockerRepository.normalizePersonName(name), Date.now());
  }

  async bindSyncIdentity(
    manifestIdentityId: string,
    manifestName: string,
    codeSalt: string,
    code: string,
    personId: number,
  ): Promise<SyncIdentity> {
    const identity: SyncIdentity = {
      identityId: manifestIdentityId,
      personId,
      displayName: manifestName,
      code,
      codeSalt,
      createdAt: Date.now(),
      lastExportedAt: null,
    };
    await this.dao.upsertSyncIdentity(identity);
    return identity;
  }

  /**
   * 应用勾选的差异。整体在一个事务里执行；
   * 号码撞全局唯一约束时跳过，并把被跳过的日期作为提示返回。
   */
  async applySyncImport(
    personId: number,
    payloadTypes: EventTypeEntity[],
    selected: SyncChangeInput[],
    loadPhoto: (epochDay: number) => Uint8Array | null,
    loadVideo: (epochDay: number) => Uint8Array | null,
    onProgress: (done: number, total: number) => void,
  ): Promise<string[]> {
    return this.dao.transaction(async () => {
      const existingTypes = await this.dao.getEventTypes();
      const existingKeys = new Set<string>(existingTypes.map((t: EventTypeEntity) => t.typeKey));
      const incomingKeys = new Set<string>();
      for (const change of selected) {
        const incoming = change.incomingEvent;
        if (incoming !== null) {
          incomingKeys.add(incoming.type);
        }
      }
      for (const typeKey of Array.from(incomingKeys)) {
        if (existingKeys.has(typeKey)) {
          continue;
        }
        const found = payloadTypes.find((t: EventTypeEntity) => t.typeKey === typeKey);
        if (found !== undefined) {
          await this.dao.insertEventType({
            id: 0,
            typeKey: found.typeKey,
            name: found.name,
            colorArgb: found.colorArgb,
            sortOrder: found.sortOrder,
            builtIn: found.builtIn,
          });
        }
      }

      const skipped: string[] = [];
      for (let index = 0; index < selected.length; index++) {
        const change = selected[index];
        switch (change.category) {
          case 'SEGMENT':
            await this.applySegmentChange(personId, change);
            break;
          case 'EVENT':
            await this.applyEventChange(personId, change);
            break;
          case 'PHOTO':
            await this.applyPhotoChange(personId, change, loadPhoto, loadVideo);
            break;
          case 'RANDOM_NUMBER':
            await this.applyNumberChange(personId, change, skipped);
            break;
          default:
            break;
        }
        onProgress(index + 1, selected.length);
      }
      return skipped;
    });
  }

  private async applySegmentChange(personId: number, change: SyncChangeInput): Promise<void> {
    if (change.action === 'ADD') {
      const incoming = change.incomingSegment;
      if (incoming === null) {
        return;
      }
      await this.dao.insertSegment({
        id: 0,
        personId,
        startAt: incoming.startAt,
        endAt: incoming.endAt,
      });
    } else if (change.action === 'REMOVE') {
      const local = change.localSegment;
      if (local !== null) {
        await this.dao.deleteSegment(local.id);
      }
    } else {
      const local = change.localSegment;
      const incoming = change.incomingSegment;
      if (local !== null && incoming !== null) {
        await this.dao.updateSegment({
          id: local.id,
          personId: local.personId,
          startAt: local.startAt,
          endAt: incoming.endAt,
        });
      }
    }
  }

  private async applyEventChange(personId: number, change: SyncChangeInput): Promise<void> {
    if (change.action === 'ADD') {
      const incoming = change.incomingEvent;
      if (incoming === null) {
        return;
      }
      await this.dao.insertEvent({
        id: 0,
        personId,
        type: incoming.type,
        occurredAt: incoming.occurredAt,
        note: incoming.note !== null && incoming.note.trim().length > 0 ? incoming.note : null,
      });
    } else if (change.action === 'REMOVE') {
      const local = change.localEvent;
      if (local !== null) {
        await this.dao.deleteEvent(local.id);
      }
    } else {
      const local = change.localEvent;
      const incoming = change.incomingEvent;
      if (local !== null && incoming !== null) {
        await this.dao.updateEvent({
          id: local.id,
          personId: local.personId,
          type: incoming.type,
          occurredAt: incoming.occurredAt,
          note: incoming.note !== null && incoming.note.trim().length > 0 ? incoming.note : null,
        });
      }
    }
  }

  private async applyPhotoChange(
    personId: number,
    change: SyncChangeInput,
    loadPhoto: (epochDay: number) => Uint8Array | null,
    loadVideo: (epochDay: number) => Uint8Array | null,
  ): Promise<void> {
    if (change.action === 'REMOVE') {
      const day = change.localPhotoDay;
      if (day !== null) {
        await this.dao.deleteDailyPhoto(personId, day);
      }
      return;
    }
    const incoming = change.incomingPhoto;
    if (incoming === null) {
      return;
    }
    const jpeg = loadPhoto(incoming.epochDay);
    if (jpeg === null) {
      return;
    }
    const video = incoming.videoFingerprint === undefined ? null : loadVideo(incoming.epochDay);
    if (incoming.videoFingerprint !== undefined && video === null) {
      throw new Error('同步数据缺少实况视频');
    }
    await this.dao.upsertDailyPhoto({
      personId,
      epochDay: incoming.epochDay,
      jpeg,
      video,
      updatedAt: Date.now(),
      fingerprint: incoming.fingerprint.length > 0
        ? incoming.fingerprint
        : DailyReport.photoFingerprint(jpeg),
      videoFingerprint: video === null ? '' : DailyReport.photoFingerprint(video),
    });
  }

  private async applyNumberChange(
    personId: number,
    change: SyncChangeInput,
    skipped: string[],
  ): Promise<void> {
    const incoming = change.incomingNumber;
    if (change.action === 'ADD') {
      if (incoming === null) {
        return;
      }
      const conflict = await this.dao.findNumberConflict(
        incoming.epochDay, incoming.number, personId,
      );
      if (conflict !== null) {
        skipped.push(LockerRepository.describeDay(incoming.epochDay));
        return;
      }
      const inserted = await this.dao.insertDailyRandomNumber({
        personId,
        epochDay: incoming.epochDay,
        number: incoming.number,
        createdAt: incoming.createdAt,
      });
      if (!inserted) {
        skipped.push(LockerRepository.describeDay(incoming.epochDay));
      }
      return;
    }
    if (change.action === 'MODIFY') {
      const local = change.localNumber;
      if (local === null || incoming === null) {
        return;
      }
      const conflict = await this.dao.findNumberConflict(
        incoming.epochDay, incoming.number, personId,
      );
      if (conflict !== null) {
        skipped.push(LockerRepository.describeDay(incoming.epochDay));
        return;
      }
      await this.dao.updateDailyRandomNumber({
        personId: local.personId,
        epochDay: local.epochDay,
        number: incoming.number,
        createdAt: local.createdAt,
      });
      return;
    }
    const local = change.localNumber;
    if (local !== null) {
      await this.dao.deleteDailyRandomNumber(personId, local.epochDay);
    }
  }

  private static describeDay(epochDay: number): string {
    const utc = new Date(epochDay * 86_400_000);
    return `${utc.getUTCMonth() + 1}月${utc.getUTCDate()}日`;
  }

  /**
   * 收集要导出的同步数据。
   * 分段用「与窗口相交」而不是「开始时间落在窗口内」：
   * 否则 0 点前锁上、早上开锁的那一天会缺少分段。
   */
  async collectSyncExport(
    person: Person,
    fromEpochDay: number,
    toEpochDay: number,
  ): Promise<SyncExportData> {
    const fromMillis = startOfDayMillis(fromEpochDay);
    const toMillis = endOfDayMillis(toEpochDay) - 1;
    const identity = await this.ensureSyncIdentity(person);
    const segments = await this.dao.getSegmentsOverlapping(person.id, fromMillis, toMillis + 1);
    const events = await this.dao.getEventsInRange(person.id, fromMillis, toMillis + 1);
    const photos = await this.photoMetas(person.id, fromEpochDay, toEpochDay);
    const numbers = await this.dao.getDailyRandomNumbersInRange(
      person.id,
      fromEpochDay,
      toEpochDay,
    );
    const usedTypes = new Set<string>();
    for (const event of events) {
      usedTypes.add(event.type);
    }
    const allTypes = await this.dao.getEventTypes();
    const types = allTypes.filter((t: EventTypeEntity) => usedTypes.has(t.typeKey));
    const data: SyncExportData = {
      identity,
      person,
      eventTypes: types,
      segments,
      events,
      randomNumbers: numbers,
      photos,
    };
    return data;
  }

  /** 读取本地指定人物在窗口内的数据，供导入差异比对使用。 */
  async collectLocalRange(
    personId: number | null,
    fromEpochDay: number,
    toEpochDay: number,
  ): Promise<SyncLocalSlice> {
    if (personId === null) {
      const empty: SyncLocalSlice = {
        segments: [],
        events: [],
        photos: [],
        numbers: [],
      };
      return empty;
    }
    const fromMillis = startOfDayMillis(fromEpochDay);
    const toMillis = endOfDayMillis(toEpochDay) - 1;
    const slice: SyncLocalSlice = {
      segments: await this.dao.getSegmentsOverlapping(personId, fromMillis, toMillis + 1),
      events: await this.dao.getEventsInRange(personId, fromMillis, toMillis + 1),
      photos: await this.photoMetas(personId, fromEpochDay, toEpochDay),
      numbers: await this.dao.getDailyRandomNumbersInRange(
        personId,
        fromEpochDay,
        toEpochDay,
      ),
    };
    return slice;
  }

  /** 发送前预检：窗口内的照片数量与体积。 */
  async syncExportEstimate(
    personId: number,
    fromEpochDay: number,
    toEpochDay: number,
    includeVideos: boolean = true,
  ): Promise<SyncExportEstimate> {
    const metas = await this.photoMetaDetails(personId, fromEpochDay, toEpochDay);
    let bytes = 0;
    for (const meta of metas) {
      bytes += meta.byteSize + (includeVideos ? meta.videoByteSize : 0);
    }
    const estimate: SyncExportEstimate = { photoCount: metas.length, photoBytes: bytes };
    return estimate;
  }

  async getDailyPhotoBytes(personId: number, epochDay: number): Promise<Uint8Array | null> {
    const photo = await this.dao.getDailyPhoto(personId, epochDay);
    return photo === null ? null : photo.jpeg;
  }

  async getDailyPhotoVideo(personId: number, epochDay: number): Promise<Uint8Array | null> {
    return this.dao.getDailyPhotoVideo(personId, epochDay);
  }

  private async photoMetas(
    personId: number,
    fromEpochDay: number,
    toEpochDay: number,
  ): Promise<SyncPhotoRef[]> {
    const details = await this.photoMetaDetails(personId, fromEpochDay, toEpochDay);
    return details.map((meta): SyncPhotoRef => {
      const ref: SyncPhotoRef = {
        epochDay: meta.epochDay,
        fingerprint: meta.fingerprint,
        videoFingerprint: meta.videoFingerprint.length > 0 ? meta.videoFingerprint : undefined,
      };
      return ref;
    });
  }

  /**
   * 照片元信息。旧数据没有指纹（迁移时留空）时按需补算并写回，
   * 之后比对与打包都不再读取照片字节。
   */
  private async photoMetaDetails(
    personId: number,
    fromEpochDay: number,
    toEpochDay: number,
  ): Promise<DailyPhotoMeta[]> {
    const metas = await this.dao.getDailyPhotoMetas(personId, fromEpochDay, toEpochDay);
    const result: DailyPhotoMeta[] = [];
    for (const meta of metas) {
      if (meta.fingerprint.length > 0) {
        result.push(meta);
        continue;
      }
      const photo = await this.dao.getDailyPhoto(personId, meta.epochDay);
      const fingerprint = photo === null ? '' : DailyReport.photoFingerprint(photo.jpeg);
      if (photo !== null && fingerprint.length > 0) {
        await this.dao.updateDailyPhotoFingerprint(photo.personId, photo.epochDay, fingerprint);
      }
      result.push({
        epochDay: meta.epochDay,
        updatedAt: meta.updatedAt,
        fingerprint,
        byteSize: meta.byteSize,
        videoByteSize: meta.videoByteSize,
        videoFingerprint: meta.videoFingerprint,
      });
    }
    return result;
  }

  // ---------------- 工具 ----------------

  /** 10000–99999。用加密安全随机源，避免可预测。 */
  private static generateFiveDigitNumber(): string {
    return generateFiveDigitNumber();
  }

  /** UUID v4，仅用于本地标识，不要求密码学强度。 */
  static uuid(): string {
    return randomUuid();
  }

  private static normalizePersonName(name: string): string {
    const trimmed = name.trim();
    if (trimmed.length === 0) {
      throw new Error('人物名称不能为空');
    }
    if (trimmed.length > 20) {
      throw new Error('人物名称最多 20 个字符');
    }
    return trimmed;
  }

  private static normalizeEventTypeName(name: string): string {
    const trimmed = name.trim();
    if (trimmed.length === 0) {
      throw new Error('事件名称不能为空');
    }
    if (trimmed.length > 20) {
      throw new Error('事件名称最多 20 个字符');
    }
    return trimmed;
  }
}

function copyPerson(person: Person, name: string, createdAt: number): Person {
  const copy: Person = { id: person.id, name, createdAt };
  return copy;
}

/** JSON 备份的数据结构，字段与 Android 版 schemaVersion 4 一致。 */
export interface BackupData {
  persons: Person[];
  eventTypes: EventTypeEntity[];
  segments: WearSegment[];
  events: EventRecord[];
  dailyRandomNumbers: DailyRandomNumber[];
}

/**
 * 一条待应用的同步差异。
 * 用字符串字面量联合类型而不是枚举，避免与 SyncDiff 里的枚举互相导入。
 */
export class SyncChangeInput {
  key: string;
  category: string;
  action: string;
  localSegment: WearSegment | null;
  localEvent: EventRecord | null;
  localPhotoDay: number | null;
  localNumber: DailyRandomNumber | null;
  incomingSegment: WearSegment | null;
  incomingEvent: EventRecord | null;
  incomingPhoto: SyncPhotoRef | null;
  incomingNumber: DailyRandomNumber | null;

  constructor(
    key: string,
    category: string,
    action: string,
    localSegment: WearSegment | null,
    localEvent: EventRecord | null,
    localPhotoDay: number | null,
    localNumber: DailyRandomNumber | null,
    incomingSegment: WearSegment | null,
    incomingEvent: EventRecord | null,
    incomingPhoto: SyncPhotoRef | null,
    incomingNumber: DailyRandomNumber | null,
  ) {
    this.key = key;
    this.category = category;
    this.action = action;
    this.localSegment = localSegment;
    this.localEvent = localEvent;
    this.localPhotoDay = localPhotoDay;
    this.localNumber = localNumber;
    this.incomingSegment = incomingSegment;
    this.incomingEvent = incomingEvent;
    this.incomingPhoto = incomingPhoto;
    this.incomingNumber = incomingNumber;
  }
}
