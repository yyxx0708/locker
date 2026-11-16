import { common } from '@kit.AbilityKit';
import { picker } from '@kit.CoreFileKit';
import { promptAction } from '@kit.ArkUI';
import { Palette } from '../common/Palette';
import { AppState } from './AppState';
import { SyncChangeInput } from '../data/LockerRepository';
import {
  DailyRandomNumber,
  EventRecord,
  Person,
  WearSegment,
} from '../data/Models';
import {
  SyncRange,
  incrementalWindow,
  isSingleDay,
  parseSyncRange,
  syncRangeLabel,
  syncRangeValues,
  windowFor,
  windowLabel,
} from '../domain/SyncRange';
import { todayEpochDay } from '../common/Dates';
import { formatMonthDayTime, formatBytes } from '../common/TimeFormat';
import { AppPreferences } from '../data/AppPreferences';
import { FileUtil } from '../common/FileUtil';
import { SyncCrypto } from '../data/SyncCrypto';
import { SyncArchive, ParsedImport } from '../data/SyncArchive';
import {
  SyncManifest,
  SyncPackageCodec,
  SyncPayload,
  SyncPhotoRef,
} from '../data/SyncPackage';
import {
  computeSyncChanges,
  defaultSelectedSyncKeys,
  SyncAction,
  SyncChange,
  SyncCategory,
} from '../data/SyncDiff';

/**
 * 掌管者同步。
 *
 * 发送：当前人物 + 所选范围 → 打包加密 zip → 系统分享面板。
 * 接收：选文件 → 读清单 → 验证身份码 → 解密 → 逐条比对 → 勾选应用。
 *
 * 所有加解密与 zip 结构都与 Android 版一致，两端的包可以互相收发。
 */
export class SyncService {
  /** 打包当前人物的所选范围，返回 zip 路径。 */
  static async exportPackage(
    context: common.UIAbilityContext,
    state: AppState,
    range: SyncRange,
    incremental: boolean,
    preserveMoving: boolean,
    onProgress: (percent: number) => void,
  ): Promise<ExportResult> {
    const person = state.currentPerson;
    if (person === null) {
      throw new Error('请先选择人物');
    }
    const identity = await state.prepareSyncIdentity();
    const today = todayEpochDay();
    const window = incremental
      ? incrementalWindow(range, today, identity.lastExportedAt)
      : windowFor(range, today);
    const estimate = await state.estimateExport(window.from, window.to, preserveMoving);
    if (estimate.photoCount > SyncArchive.MAX_PHOTOS) {
      throw new Error(`照片数量 ${estimate.photoCount} 张，超过单次上限 ` +
        `${SyncArchive.MAX_PHOTOS} 张，请缩短数据范围`);
    }
    if (estimate.photoBytes > 260 * 1024 * 1024) {
      throw new Error(`照片约 ${Math.round(estimate.photoBytes / (1024 * 1024))} MB，` +
        '超过单次上限 260 MB，请缩短数据范围');
    }

    const data = await state.collectExport(person.id, window.from, window.to);
    const manifest: SyncManifest = {
      identityId: identity.identityId,
      identityName: identity.displayName,
      codeSalt: identity.codeSalt,
      codeHash: SyncCrypto.hashCode(identity.codeSalt, identity.code),
      exportedAt: Date.now(),
      fromEpochDay: window.from,
      toEpochDay: window.to,
    };
    const segments: WearSegment[] = data.segments.map((s: WearSegment): WearSegment => {
      const item: WearSegment = { id: 0, personId: 0, startAt: s.startAt, endAt: s.endAt };
      return item;
    });
    const events: EventRecord[] = data.events.map((e: EventRecord): EventRecord => {
      const item: EventRecord = {
        id: 0,
        personId: 0,
        type: e.type,
        occurredAt: e.occurredAt,
        note: e.note,
      };
      return item;
    });
    const numbers: DailyRandomNumber[] = data.randomNumbers.map(
      (n: DailyRandomNumber): DailyRandomNumber => {
        const item: DailyRandomNumber = {
          personId: 0,
          epochDay: n.epochDay,
          number: n.number,
          createdAt: n.createdAt,
        };
        return item;
      });
    const photos: SyncPhotoRef[] = data.photos.map((photo: SyncPhotoRef): SyncPhotoRef => {
      const ref: SyncPhotoRef = {
        epochDay: photo.epochDay,
        fingerprint: photo.fingerprint,
      };
      if (preserveMoving && photo.videoFingerprint !== undefined) {
        ref.videoFingerprint = photo.videoFingerprint;
      }
      return ref;
    });
    const payload: SyncPayload = {
      personName: person.name,
      eventTypes: data.eventTypes,
      segments,
      events,
      randomNumbers: numbers,
      photos,
    };
    const payloadJson = SyncPackageCodec.encodePayloadJson(payload);
    const keyBytes = SyncCrypto.deriveKeyBytes(identity.codeSalt, identity.code);

    const workDir = FileUtil.join(context.cacheDir, 'sync');
    FileUtil.ensureDir(workDir);
    // 只保留最近几个包：分享出去的 URI 可能被对方稍后才读取，不能立刻删掉
    const files: string[] = FileUtil.listFiles(workDir)
      .filter((name: string) => name.startsWith('locker-') && name.endsWith('.zip'));
    for (const name of files) {
      FileUtil.remove(FileUtil.join(workDir, name));
    }

    const target = FileUtil.join(workDir, `locker-${SyncService.stamp()}.zip`);
    onProgress(0);
    await SyncArchive.writePackage(
      target,
      workDir,
      manifest,
      payloadJson,
      photos,
      keyBytes,
      (epochDay: number) => state.getDailyPhotoBytes(person.id, epochDay),
      (epochDay: number) => state.getDailyPhotoVideo(person.id, epochDay),
      (done: number, total: number) => {
        onProgress(total <= 0 ? 100 : Math.floor((done * 100) / total));
      },
    );
    await state.markIdentityExported(identity.identityId);
    onProgress(100);
    const exportResult: ExportResult = {
      path: target,
      identityCode: identity.code,
      photoCount: data.photos.length,
      dayCount: window.to - window.from + 1,
    };
    return exportResult;
  }

  /** 读清单并判断是否需要验证码。 */
  static async previewImport(
    context: common.UIAbilityContext,
    state: AppState,
    zipPath: string,
  ): Promise<ImportPreview> {
    const workDir = FileUtil.join(context.cacheDir, 'sync');
    FileUtil.ensureDir(workDir);
    const manifest = await SyncArchive.readManifest(zipPath, workDir);
    const binding = await state.getSyncIdentity(manifest.identityId);
    const boundPerson = binding === null ? null : await state.getPersonById(binding.personId);
    const codeChanged = binding !== null &&
      SyncCrypto.hashCode(binding.codeSalt, binding.code) !== manifest.codeHash;
    const occupiedIds = new Set<number>();
    for (const identity of await state.getAllSyncIdentities()) {
      if (identity.identityId !== manifest.identityId) {
        occupiedIds.add(identity.personId);
      }
    }
    const sameNamePersons: PersonLike[] = state.persons.filter(
      (p: Person) => p.name.trim() === manifest.identityName.trim(),
    );
    const availablePersons: PersonLike[] = state.persons.filter(
      (p: Person) => !occupiedIds.has(p.id),
    );
    const alreadyImported = AppPreferences.lastImportHash(manifest.identityId) !== null;
    const preview: ImportPreview = {
      manifest,
      boundPerson,
      sameNamePersons,
      availablePersons,
      needsVerification: binding === null || codeChanged,
      codeChanged,
      alreadyImported,
      zipPath,
    };
    return preview;
  }

  /** 验证身份码并计算差异。 */
  static async verifyAndDiff(
    context: common.UIAbilityContext,
    state: AppState,
    preview: ImportPreview,
    code: string | null,
    targetPersonId: number | null,
  ): Promise<ImportDiff> {
    const manifest = preview.manifest;
    const binding = await state.getSyncIdentity(manifest.identityId);
    let effectiveCode: string;
    if (code !== null && code.trim().length > 0) {
      if (!SyncCrypto.matches(manifest.codeSalt, manifest.codeHash, code)) {
        throw new Error('验证码不正确');
      }
      effectiveCode = SyncCrypto.normalizeCode(code);
    } else if (binding !== null) {
      if (SyncCrypto.hashCode(binding.codeSalt, binding.code) !== manifest.codeHash) {
        throw new Error('验证码已更换，需要重新验证');
      }
      effectiveCode = binding.code;
    } else {
      throw new Error('请输入验证码');
    }

    const workDir = FileUtil.join(context.cacheDir, 'sync');
    const keyBytes = SyncCrypto.deriveKeyBytes(manifest.codeSalt, effectiveCode);
    const parsed = await SyncArchive.parseEncryptedPayload(
      preview.zipPath,
      workDir,
      keyBytes,
      () => {
      },
    );
    if (!SyncArchive.verifyPhotoEntries(parsed.innerDir, parsed.payload)) {
      throw new Error('同步包内的照片与清单不一致，请让对方重新打包');
    }

    await SyncService.validateTarget(state, manifest.identityId, targetPersonId);
    const personId = targetPersonId;
    const targetPerson = personId === null ? null : await state.getPersonById(personId);
    const local = await state.collectLocalRange(personId, manifest.fromEpochDay, manifest.toEpochDay);
    const localTypes = await state.getEventTypesList();
    const typeNames = new Map<string, string>();
    for (const type of localTypes) {
      typeNames.set(type.typeKey, type.name);
    }
    for (const type of parsed.payload.eventTypes) {
      if (!typeNames.has(type.typeKey)) {
        typeNames.set(type.typeKey, type.name);
      }
    }
    const changes = computeSyncChanges(
      local.segments,
      local.events,
      local.photos,
      local.numbers,
      parsed.payload,
      typeNames,
    );
    const result: ImportDiff = {
      personName: parsed.payload.personName,
      targetName: targetPerson === null ? parsed.payload.personName : targetPerson.name,
      changes,
      photoCount: parsed.photos.length,
      parsed,
      targetPersonId: personId,
      code: effectiveCode,
      manifest,
    };
    return result;
  }

  /** 应用勾选的差异。 */
  static async apply(
    state: AppState,
    diff: ImportDiff,
    selectedKeys: Set<string>,
    onProgress: (percent: number) => void,
  ): Promise<ApplyResult> {
    await SyncService.validateTarget(state, diff.manifest.identityId, diff.targetPersonId);
    const personId = diff.targetPersonId ?? await state.createPersonForImport(diff.personName);
    await state.bindSyncIdentity(
      diff.manifest.identityId,
      diff.manifest.identityName,
      diff.manifest.codeSalt,
      diff.code,
      personId,
    );
    const selected: SyncChangeInput[] = diff.changes
      .filter((c: SyncChange) => selectedKeys.has(c.key))
      .map((c: SyncChange): SyncChangeInput => {
        const input: SyncChangeInput = new SyncChangeInput(
          c.key,
          `${c.category}`,
          `${c.action}`,
          c.localSegment ?? null,
          c.localEvent ?? null,
          c.localPhotoDay ?? null,
          c.localNumber ?? null,
          c.incomingSegment ?? null,
          c.incomingEvent ?? null,
          c.incomingPhoto ?? null,
          c.incomingNumber ?? null,
        );
        return input;
      });
    onProgress(0);
    const skipped = await state.applySyncImport(
      personId,
      diff.parsed.payload.eventTypes,
      selected,
      (epochDay: number) => SyncArchive.readPhoto(diff.parsed.innerDir, epochDay),
      (epochDay: number) => SyncArchive.readVideo(diff.parsed.innerDir, epochDay),
      (done: number, total: number) => {
        onProgress(total <= 0 ? 100 : Math.floor((done * 100) / total));
      },
    );
    await state.selectPersonById(personId);
    onProgress(100);
    const applyResult: ApplyResult = {
      added: selected.filter((c: SyncChangeInput) => c.action === SyncAction.ADD).length,
      removed: selected.filter((c: SyncChangeInput) => c.action === SyncAction.REMOVE).length,
      modified: selected.filter((c: SyncChangeInput) => c.action === SyncAction.MODIFY).length,
      skippedNumberDays: skipped,
    };
    return applyResult;
  }

  private static async validateTarget(
    state: AppState,
    identityId: string,
    personId: number | null,
  ): Promise<void> {
    if (personId === null) {
      return;
    }
    if (await state.getPersonById(personId) === null) {
      throw new Error('所选人物已不存在，请重新选择');
    }
    for (const identity of await state.getAllSyncIdentities()) {
      if (identity.personId === personId && identity.identityId !== identityId) {
        throw new Error('所选人物已绑定其他同步身份，请重新选择');
      }
    }
  }

  static stamp(): string {
    const d = new Date();
    return `${d.getFullYear()}${(d.getMonth() + 1).toString().padStart(2, '0')}` +
      `${d.getDate().toString().padStart(2, '0')}-` +
      `${d.getHours().toString().padStart(2, '0')}${d.getMinutes().toString().padStart(2, '0')}`;
  }
}

export interface ExportResult {
  path: string;
  identityCode: string;
  photoCount: number;
  dayCount: number;
}

export interface ImportPreview {
  manifest: SyncManifest;
  boundPerson: PersonLike | null;
  sameNamePersons: PersonLike[];
  availablePersons: PersonLike[];
  needsVerification: boolean;
  codeChanged: boolean;
  alreadyImported: boolean;
  zipPath: string;
}

export interface ImportDiff {
  personName: string;
  targetName: string;
  changes: SyncChange[];
  photoCount: number;
  parsed: ParsedImport;
  targetPersonId: number | null;
  code: string;
  manifest: SyncManifest;
}

export interface ApplyResult {
  added: number;
  removed: number;
  modified: number;
  skippedNumberDays: string[];
}

interface PersonLike {
  id: number;
  name: string;
}
