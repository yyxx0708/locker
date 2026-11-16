import {
  DailyPhoto,
  DailyRandomNumber,
  EventRecord,
  EventTypeEntity,
  Person,
  SyncIdentity,
  WearSegment,
} from '../data/Models';
import { LockerRepository, SyncChangeInput } from '../data/LockerRepository';
import { DailyReport, DailyReportData, PhotoCheck } from '../data/DailyReport';
import { SyncCrypto } from '../data/SyncCrypto';
import { AppPreferences } from '../data/AppPreferences';
import { ThemeMode } from '../common/ThemeMode';
import { ThemeController } from '../common/Palette';
import { StatsMode } from '../domain/Stats';
import { YearMonth, todayEpochDay, startOfDayMillis, endOfDayMillis } from '../common/Dates';
import { BackupJson } from '../data/BackupJson';
import { recognizeNumberCandidates } from '../common/NumberOcr';
import { SyncPhotoRef } from '../data/SyncPackage';
import { WidgetData } from '../common/WidgetData';
import { common } from '@kit.AbilityKit';

/**
 * 应用状态。
 *
 * 用 @Observed 标记，组件通过 @ObjectLink 订阅，字段变化会自动刷新界面；
 * 所有写操作先走 Repository 的校验与事务，成功后再更新内存状态，
 * 避免界面先亮起来、随后又被数据库回滚打脸。
 */
@Observed
export class AppState {
  persons: Person[] = [];
  eventTypes: EventTypeEntity[] = [];
  currentPersonId: number | null = null;
  activeSegment: WearSegment | null = null;
  segmentCount: number = 0;
  eventCount: number = 0;
  private personSelectionToken: number = 0;
  themeMode: ThemeMode = ThemeMode.SYSTEM;
  statsMode: StatsMode = StatsMode.MONTH;
  statsAnchorKey: string = YearMonth.now().key();
  loading: boolean = true;
  /** 一次性提示，由根页面消费后清空。 */
  message: string = '';
  messageSeq: number = 0;
  /** 同步打包/校验进度 0-100，null 表示没有进行中的任务。 */
  syncProgress: number = -1;
  /** 打卡页请求打开发送弹窗。 */
  sendTodayRequested: boolean = false;

  private repo: LockerRepository;
  /** 用于刷新桌面卡片；由根页面注入。 */
  private context: common.Context | null = null;

  constructor(repo: LockerRepository) {
    this.repo = repo;
  }

  setContext(context: common.Context): void {
    this.context = context;
  }

  /**
   * 刷新桌面卡片。与 Android 版一致：锁上/开锁、切换人物、改人物、上传照片后都要刷新。
   * 卡片刷新失败不影响主流程，因此不抛出。
   */
  private refreshWidgets(): void {
    const context = this.context;
    if (context === null) {
      return;
    }
    WidgetData.refreshAll(context).catch(() => {
    });
  }

  get statsAnchor(): YearMonth {
    const parts = this.statsAnchorKey.split('-');
    return new YearMonth(Number(parts[0]), Number(parts[1]));
  }

  set statsAnchor(anchor: YearMonth) {
    this.statsAnchorKey = anchor.key();
  }

  get currentPerson(): Person | null {
    const id = this.currentPersonId;
    if (id === null) {
      return this.persons.length > 0 ? this.persons[0] : null;
    }
    const found = this.persons.find((p: Person) => p.id === id);
    return found === undefined ? (this.persons.length > 0 ? this.persons[0] : null) : found;
  }

  // ---------------- 初始化 ----------------

  async load(): Promise<void> {
    ThemeController.load();
    this.themeMode = ThemeController.current();
    await this.reloadPersons();
    this.currentPersonId = AppPreferences.selectedPersonId();
    const person = this.currentPerson;
    if (person !== null && this.currentPersonId !== person.id) {
      this.currentPersonId = person.id;
      AppPreferences.setSelectedPersonId(person.id);
    }
    await this.reloadEventTypes();
    await this.refreshPersonState();
    this.loading = false;
    const current = this.currentPerson;
    if (current !== null) {
      await this.ensureDailyRandomNumber(todayEpochDay());
    }
  }

  async reloadPersons(): Promise<void> {
    this.persons = await this.repo.getPersons();
  }

  async reloadEventTypes(): Promise<void> {
    this.eventTypes = await this.repo.getEventTypes();
  }

  /** 当前人物的佩戴状态与计数。 */
  async refreshPersonState(): Promise<void> {
    const person = this.currentPerson;
    if (person === null) {
      this.activeSegment = null;
      this.segmentCount = 0;
      this.eventCount = 0;
      return;
    }
    this.activeSegment = await this.repo.getActiveSegment(person.id);
    this.segmentCount = await this.repo.countSegments(person.id);
    this.eventCount = await this.repo.countEvents(person.id);
  }

  // ---------------- 提示 ----------------

  notify(message: string): void {
    this.message = message;
    this.messageSeq++;
  }

  // ---------------- 人物 ----------------

  async selectPerson(person: Person): Promise<void> {
    const token = ++this.personSelectionToken;
    const activeSegment = await this.repo.getActiveSegment(person.id);
    const segmentCount = await this.repo.countSegments(person.id);
    const eventCount = await this.repo.countEvents(person.id);
    if (token !== this.personSelectionToken) {
      return;
    }

    // 目标数据就绪后再一起发布人物与计时状态，避免串到上一人物的数据。
    this.currentPersonId = person.id;
    this.activeSegment = activeSegment;
    this.segmentCount = segmentCount;
    this.eventCount = eventCount;
    AppPreferences.setSelectedPersonId(person.id);
    await this.ensureDailyRandomNumber(todayEpochDay());
  }

  async selectPersonById(personId: number): Promise<void> {
    const person = this.persons.find((p: Person) => p.id === personId);
    if (person === undefined) {
      return;
    }
    await this.selectPerson(person);
  }

  async addPerson(name: string): Promise<void> {
    await this.run(async () => {
      const id = await this.repo.addPerson(name);
      await this.reloadPersons();
      this.currentPersonId = id;
      AppPreferences.setSelectedPersonId(id);
      await this.refreshPersonState();
      await this.ensureDailyRandomNumber(todayEpochDay());
    }, '新建人物失败');
  }

  async renamePerson(person: Person, name: string): Promise<void> {
    await this.run(async () => {
      await this.repo.renamePerson(person.id, name);
      await this.reloadPersons();
      this.refreshWidgets();
    }, '改名失败');
  }

  async deletePerson(person: Person): Promise<void> {
    await this.run(async () => {
      await this.repo.deletePerson(person);
      await this.reloadPersons();
      this.refreshWidgets();
      if (this.currentPersonId === person.id) {
        const next = this.persons.length > 0 ? this.persons[0].id : null;
        this.currentPersonId = next;
        AppPreferences.setSelectedPersonId(next);
      }
      await this.refreshPersonState();
    }, '删除人物失败');
  }

  // ---------------- 事件类型 ----------------

  async addEventType(name: string, colorArgb: number): Promise<void> {
    await this.run(async () => {
      await this.repo.addEventType(name, colorArgb);
      await this.reloadEventTypes();
    }, '新建事件类型失败');
  }

  async updateEventType(type: EventTypeEntity, name: string, colorArgb: number): Promise<void> {
    await this.run(async () => {
      await this.repo.updateEventType(type.typeKey, name, colorArgb);
      await this.reloadEventTypes();
    }, '修改事件类型失败');
  }

  async deleteEventType(type: EventTypeEntity): Promise<void> {
    await this.run(async () => {
      await this.repo.deleteEventType(type);
      await this.reloadEventTypes();
      await this.refreshPersonState();
    }, '删除事件类型失败');
  }

  async countEventsOfType(typeKey: string): Promise<number> {
    return this.repo.countEventsOfType(typeKey);
  }

  // ---------------- 佩戴 ----------------

  async startWearing(): Promise<void> {
    const person = this.currentPerson;
    if (person === null) {
      return;
    }
    await this.run(async () => {
      await this.repo.startWearing(person.id, Date.now());
      await this.refreshPersonState();
      this.refreshWidgets();
    }, '锁上失败');
  }

  async stopWearing(): Promise<void> {
    const person = this.currentPerson;
    if (person === null) {
      return;
    }
    await this.run(async () => {
      await this.repo.stopWearing(person.id, Date.now());
      await this.refreshPersonState();
      this.refreshWidgets();
    }, '开锁失败');
  }

  async addManualSegment(startAt: number, endAt: number | null): Promise<void> {
    const person = this.currentPerson;
    if (person === null) {
      return;
    }
    await this.run(async () => {
      await this.repo.addManualSegment(person.id, startAt, endAt);
      await this.refreshPersonState();
      this.refreshWidgets();
    }, '补录分段失败');
  }

  async updateSegment(id: number, personId: number, startAt: number, endAt: number | null): Promise<void> {
    await this.run(async () => {
      await this.repo.updateSegment(id, personId, startAt, endAt);
      await this.refreshPersonState();
    }, '保存分段失败');
  }

  async deleteSegment(segment: WearSegment): Promise<void> {
    await this.run(async () => {
      await this.repo.deleteSegment(segment);
      await this.refreshPersonState();
      this.refreshWidgets();
    }, '删除分段失败');
  }

  // ---------------- 事件 ----------------

  async addEvent(
    typeKey: string,
    occurredAt: number,
    note: string | null,
  ): Promise<number | null> {
    const person = this.currentPerson;
    if (person === null) {
      return null;
    }
    let created: number | null = null;
    await this.run(async () => {
      created = await this.repo.addEvent(person.id, typeKey, occurredAt, note);
      await this.refreshPersonState();
    }, '记录事件失败');
    return created;
  }

  async updateEvent(
    id: number,
    personId: number,
    typeKey: string,
    occurredAt: number,
    note: string | null,
  ): Promise<void> {
    await this.run(async () => {
      await this.repo.updateEvent(id, personId, typeKey, occurredAt, note);
      await this.refreshPersonState();
    }, '保存事件失败');
  }

  async deleteEvent(id: number): Promise<void> {
    await this.run(async () => {
      await this.repo.deleteEvent(id);
      await this.refreshPersonState();
    }, '删除事件失败');
  }

  // ---------------- 主题 / 统计 ----------------

  setThemeMode(mode: ThemeMode): void {
    ThemeController.set(mode);
    this.themeMode = mode;
  }

  setStatsMode(mode: StatsMode): void {
    this.statsMode = mode;
  }

  movePeriod(delta: number): void {
    const anchor = this.statsAnchor;
    this.statsAnchor = this.statsMode === StatsMode.MONTH
      ? anchor.plusMonths(delta)
      : anchor.plusYears(delta);
  }

  goToCurrentPeriod(): void {
    this.statsAnchor = YearMonth.now();
  }

  // ---------------- 每日随机数 / 照片 ----------------

  async getDailyRandomNumber(epochDay: number): Promise<string | null> {
    const person = this.currentPerson;
    if (person === null) {
      return null;
    }
    const entry = await this.repo.getDailyRandomNumber(person.id, epochDay);
    return entry === null ? null : entry.number;
  }

  async ensureDailyRandomNumber(epochDay: number): Promise<string | null> {
    const person = this.currentPerson;
    if (person === null) {
      return null;
    }
    try {
      return await this.repo.ensureDailyRandomNumber(person.id, epochDay);
    } catch (err) {
      this.notify((err as Error).message ?? '生成随机数失败');
      return null;
    }
  }

  async setDailyPhoto(epochDay: number, jpeg: Uint8Array, video: Uint8Array | null = null): Promise<void> {
    const person = this.currentPerson;
    if (person === null) {
      return;
    }
    let uploaded = false;
    await this.run(async () => {
      await this.repo.setDailyPhoto(person.id, epochDay, jpeg, video);
      this.refreshWidgets();
      uploaded = true;
    }, '上传照片失败');
    // 开关打开时，上传完成后自动识别照片中的号码并与当日随机数比对
    if (uploaded && AppPreferences.checkNumberAfterUpload()) {
      this.checkPhotoNumber(person.id, epochDay, jpeg).catch(() => {
        // 检测失败不打扰上传流程
      });
    }
  }

  async deleteDailyPhoto(epochDay: number): Promise<void> {
    const person = this.currentPerson;
    if (person === null) {
      return;
    }
    await this.run(async () => {
      await this.repo.deleteDailyPhoto(person.id, epochDay);
      this.refreshWidgets();
    }, '删除照片失败');
  }

  async getDailyPhoto(personId: number, epochDay: number): Promise<DailyPhoto | null> {
    return this.repo.getDailyPhoto(personId, epochDay);
  }

  async getDailyPhotosInRange(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyPhoto[]> {
    return this.repo.getDailyPhotosInRange(personId, startEpochDay, endEpochDay);
  }

  async getSegmentsOverlapping(
    personId: number,
    start: number,
    endExclusive: number,
  ): Promise<WearSegment[]> {
    return this.repo.getSegmentsOverlapping(personId, start, endExclusive);
  }

  async getEventsInRange(personId: number, from: number, to: number): Promise<EventRecord[]> {
    return this.repo.getEventsInRange(personId, from, to);
  }

  async getDailyRandomNumbersInRange(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyRandomNumber[]> {
    return this.repo.getDailyRandomNumbersInRange(personId, startEpochDay, endEpochDay);
  }

  async getDailyPhotoMetas(
    personId: number,
    startEpochDay: number,
    endEpochDay: number,
  ): Promise<DailyPhoto[]> {
    return this.repo.getDailyPhotosInRange(personId, startEpochDay, endEpochDay);
  }

  // ---------------- 备份 ----------------

  async buildBackupText(): Promise<string> {
    const data = await this.repo.exportAll();
    return BackupJson.encode(data);
  }

  async importBackup(text: string): Promise<void> {
    const data = BackupJson.decode(text);
    await this.repo.importAll(data);
    this.currentPersonId = null;
    AppPreferences.setSelectedPersonId(null);
    await this.reloadPersons();
    await this.reloadEventTypes();
    await this.refreshPersonState();
    this.refreshWidgets();
  }

  // ---------------- 同步 ----------------

  async prepareSyncIdentity(): Promise<SyncIdentity> {
    const person = this.currentPerson;
    if (person === null) {
      throw new Error('请先选择人物');
    }
    return this.repo.ensureSyncIdentity(person);
  }

  async regenerateSyncCode(): Promise<SyncIdentity> {
    const person = this.currentPerson;
    if (person === null) {
      throw new Error('请先选择人物');
    }
    const identity = await this.repo.ensureSyncIdentity(person);
    return this.repo.regenerateSyncCode(identity);
  }

  async estimateExport(fromDay: number, toDay: number,
    includeVideos: boolean = true): Promise<SyncExportEstimate> {
    const person = this.currentPerson;
    if (person === null) {
      const empty: SyncExportEstimate = { photoCount: 0, photoBytes: 0 };
      return empty;
    }
    return this.repo.syncExportEstimate(person.id, fromDay, toDay, includeVideos);
  }

  async lastExportedAt(): Promise<number | null> {
    const person = this.currentPerson;
    if (person === null) {
      return null;
    }
    const identity = await this.repo.getSyncIdentityForPerson(person.id);
    return identity === null ? null : identity.lastExportedAt;
  }

  async collectExport(personId: number, fromDay: number, toDay: number): Promise<SyncExportData> {
    const person = await this.repo.getPersonById(personId);
    if (person === null) {
      throw new Error('人物不存在');
    }
    return this.repo.collectSyncExport(person, fromDay, toDay);
  }

  async markIdentityExported(identityId: string): Promise<void> {
    await this.repo.markIdentityExported(identityId, Date.now());
  }

  async getSyncIdentity(identityId: string): Promise<SyncIdentity | null> {
    return this.repo.getSyncIdentity(identityId);
  }

  async getAllSyncIdentities(): Promise<SyncIdentity[]> {
    return this.repo.getAllSyncIdentities();
  }

  async getPersonById(id: number): Promise<Person | null> {
    return this.repo.getPersonById(id);
  }

  async collectLocalRange(
    personId: number | null,
    fromDay: number,
    toDay: number,
  ): Promise<SyncLocalSlice> {
    return this.repo.collectLocalRange(personId, fromDay, toDay);
  }

  async getEventTypesList(): Promise<EventTypeEntity[]> {
    return this.repo.getEventTypes();
  }

  async getDailyPhotoBytes(personId: number, epochDay: number): Promise<Uint8Array | null> {
    return this.repo.getDailyPhotoBytes(personId, epochDay);
  }

  async getDailyPhotoVideo(personId: number, epochDay: number): Promise<Uint8Array | null> {
    return this.repo.getDailyPhotoVideo(personId, epochDay);
  }

  async applySyncImport(
    personId: number,
    payloadTypes: EventTypeEntity[],
    changes: SyncChangeInput[],
    loadPhoto: (epochDay: number) => Uint8Array | null,
    loadVideo: (epochDay: number) => Uint8Array | null,
    onProgress: (done: number, total: number) => void,
  ): Promise<string[]> {
    return this.repo.applySyncImport(personId, payloadTypes, changes, loadPhoto, loadVideo, onProgress);
  }

  async createPersonForImport(name: string): Promise<number> {
    const id = await this.repo.createPersonForImport(name);
    await this.reloadPersons();
    return id;
  }

  async bindSyncIdentity(
    identityId: string,
    name: string,
    salt: string,
    code: string,
    personId: number,
  ): Promise<void> {
    await this.repo.bindSyncIdentity(identityId, name, salt, code, personId);
  }

  // ---------------- 日报 ----------------

  async buildDailyReport(): Promise<DailyReportUi> {
    const person = this.currentPerson;
    if (person === null) {
      throw new Error('请先选择人物');
    }
    const identity = await this.repo.ensureSyncIdentity(person);
    const epochDay = todayEpochDay();
    const number = await this.repo.ensureDailyRandomNumber(person.id, epochDay);
    const dayStart = startOfDayMillis(epochDay);
    const dayEnd = endOfDayMillis(epochDay);
    const now = Date.now();
    const segments = await this.repo.getSegmentsOverlapping(person.id, dayStart, dayEnd);
    let wearMillis = 0;
    for (const segment of segments) {
      const start = Math.max(segment.startAt, dayStart);
      const end = Math.min(segment.endAt === null ? now : segment.endAt, dayEnd);
      if (end > start) {
        wearMillis += end - start;
      }
    }
    const events = await this.repo.getEventsInRange(person.id, dayStart, dayEnd);
    const photos = await this.repo.getDailyPhotosInRange(person.id, epochDay, epochDay);
    let photoCheck = PhotoCheck.NONE;
    let fingerprint = '';
    if (photos.length > 0) {
      const jpeg = photos[0].jpeg;
      fingerprint = DailyReport.photoFingerprint(jpeg);
      photoCheck = await this.verifyPhotoNumber(jpeg, number);
    }
    const report: DailyReportData = {
      identityId: identity.identityId,
      epochDay,
      number,
      wearMinutes: Math.floor(wearMillis / 60_000),
      eventCount: events.length,
      photoCheck,
      photoFingerprint: fingerprint,
      issuedAt: now,
    };
    const ui: DailyReportUi = {
      report,
      payload: DailyReport.encode(report, identity.codeSalt, identity.code),
      identityName: identity.displayName,
    };
    return ui;
  }

  async verifyDailyReport(payload: string, codeInput: string | null): Promise<ReportVerification> {
    const parsed = DailyReport.parse(payload);
    const binding = await this.repo.getSyncIdentity(parsed.report.identityId);
    const code = codeInput !== null && codeInput.trim().length > 0
      ? SyncCrypto.normalizeCode(codeInput)
      : (binding === null ? null : binding.code);
    const verification: ReportVerification = {
      identityName: binding === null ? null : binding.displayName,
      report: parsed.report,
      signatureValid: code !== null && DailyReport.verify(parsed, code),
      needsCode: code === null,
    };
    return verification;
  }

  private async verifyPhotoNumber(jpeg: Uint8Array, number: string): Promise<PhotoCheck> {
    try {
      const candidates = await recognizeNumberCandidates(jpeg);
      if (candidates.indexOf(number) >= 0) {
        return PhotoCheck.PASS;
      }
      return candidates.length === 0 ? PhotoCheck.UNKNOWN : PhotoCheck.MISMATCH;
    } catch (err) {
      return PhotoCheck.UNKNOWN;
    }
  }

  /** 上传后号码检测：结果通过 notify 提示，失败不打扰上传流程。 */
  private async checkPhotoNumber(personId: number, epochDay: number, jpeg: Uint8Array): Promise<void> {
    try {
      const entry = await this.repo.getDailyRandomNumber(personId, epochDay);
      if (entry === null) {
        return;
      }
      const check = await this.verifyPhotoNumber(jpeg, entry.number);
      if (check === PhotoCheck.PASS) {
        this.notify(`✓ 照片号码检测通过（${entry.number}）`);
      } else if (check === PhotoCheck.MISMATCH) {
        this.notify(`✗ 照片号码与当日随机数 ${entry.number} 不一致`);
      } else if (check === PhotoCheck.UNKNOWN) {
        this.notify('未能识别照片中的号码');
      }
    } catch (err) {
      // 检测失败不打扰上传流程
    }
  }

  // ---------------- 工具 ----------------

  private async run(action: () => Promise<void>, fallback: string): Promise<void> {
    try {
      await action();
    } catch (err) {
      this.notify((err as Error).message ?? fallback);
    }
  }
}

export interface SyncExportData {
  identity: SyncIdentity;
  person: Person;
  eventTypes: EventTypeEntity[];
  segments: WearSegment[];
  events: EventRecord[];
  randomNumbers: DailyRandomNumber[];
  photos: SyncPhotoRef[];
}

export interface SyncLocalSlice {
  segments: WearSegment[];
  events: EventRecord[];
  photos: SyncPhotoRef[];
  numbers: DailyRandomNumber[];
}

export interface SyncExportEstimate {
  photoCount: number;
  photoBytes: number;
}

export interface SyncChange {
  key: string;
  action: string;
}

export interface DailyReportUi {
  report: DailyReportData;
  payload: string;
  identityName: string;
}

export interface ReportVerification {
  identityName: string | null;
  report: DailyReportData;
  signatureValid: boolean;
  needsCode: boolean;
}
