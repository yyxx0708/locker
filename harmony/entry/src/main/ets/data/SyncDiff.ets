import {
  DailyRandomNumber,
  EventRecord,
  WearSegment,
} from './Models';
import { SyncPackage, SyncPayload, SyncPhotoRef } from './SyncPackage';
import { formatDateTime, formatMonthDay, formatTime } from '../common/TimeFormat';

/** 一条待确认的差异；携带应用所需的本地/远端实体。 */
export enum SyncCategory {
  SEGMENT = 'SEGMENT',
  EVENT = 'EVENT',
  PHOTO = 'PHOTO',
  RANDOM_NUMBER = 'RANDOM_NUMBER',
}

export enum SyncAction {
  ADD = 'ADD',
  REMOVE = 'REMOVE',
  MODIFY = 'MODIFY',
}

export interface SyncChange {
  key: string;
  category: SyncCategory;
  action: SyncAction;
  title: string;
  detail: string;
  localSegment?: WearSegment;
  localEvent?: EventRecord;
  localPhotoDay?: number;
  localNumber?: DailyRandomNumber;
  incomingSegment?: WearSegment;
  incomingEvent?: EventRecord;
  incomingPhoto?: SyncPhotoRef;
  incomingNumber?: DailyRandomNumber;
}

function formatEnd(endAt: number | null): string {
  return endAt === null ? '佩戴中' : formatDateTime(endAt);
}

function formatDayOfMillis(millis: number): string {
  const d = new Date(millis);
  return `${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')}`;
}

function formatDayOfEpochDay(epochDay: number): string {
  // epochDay 按本地日历解释，与 Android 版的展示保持一致
  const utc = new Date(epochDay * 86_400_000);
  return formatMonthDay(new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate()).getTime());
}

function formatTimeOfMillis(millis: number): string {
  const d = new Date(millis);
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

function formatDateTimeShort(millis: number): string {
  return `${formatDayOfMillis(millis)} ${formatTimeOfMillis(millis)}`;
}

/**
 * 按自然键配对计算本地与远端数据的差异：
 * 分段按戴上时间、事件按（类型+发生时间）、照片按日期+内容、随机数按日期+号码。
 */
export function computeSyncChanges(
  localSegments: WearSegment[],
  localEvents: EventRecord[],
  localPhotos: SyncPhotoRef[],
  localNumbers: DailyRandomNumber[],
  payload: SyncPayload,
  typeNames: Map<string, string>,
): SyncChange[] {
  const changes: SyncChange[] = [];

  // ---- 分段 ----
  const localSegByStart = new Map<number, WearSegment>();
  for (const segment of localSegments) {
    localSegByStart.set(segment.startAt, segment);
  }
  const incomingSegByStart = new Map<number, WearSegment>();
  for (const segment of payload.segments) {
    incomingSegByStart.set(segment.startAt, segment);
  }
  const segStarts = Array.from(new Set<number>([
    ...Array.from(localSegByStart.keys()),
    ...Array.from(incomingSegByStart.keys()),
  ])).sort((a, b) => a - b);
  for (const start of segStarts) {
    const local = localSegByStart.get(start);
    const incoming = incomingSegByStart.get(start);
    const title = `分段 ${formatDayOfMillis(start)} ${formatTimeOfMillis(start)}–` +
      formatEnd(incoming !== undefined ? incoming.endAt : (local !== undefined ? local.endAt : null));
    if (local === undefined && incoming !== undefined) {
      changes.push({
        key: `seg-${start}`,
        category: SyncCategory.SEGMENT,
        action: SyncAction.ADD,
        title,
        detail: '新增分段',
        incomingSegment: incoming,
      });
    } else if (local !== undefined && incoming === undefined) {
      changes.push({
        key: `seg-${start}`,
        category: SyncCategory.SEGMENT,
        action: SyncAction.REMOVE,
        title,
        detail: '删除分段',
        localSegment: local,
      });
    } else if (local !== undefined && incoming !== undefined && local.endAt !== incoming.endAt) {
      changes.push({
        key: `seg-${start}`,
        category: SyncCategory.SEGMENT,
        action: SyncAction.MODIFY,
        title,
        detail: `结束时间 ${formatEnd(local.endAt)} → ${formatEnd(incoming.endAt)}`,
        localSegment: local,
        incomingSegment: incoming,
      });
    }
  }

  // ---- 事件 ----
  const localEvByKey = new Map<string, EventRecord>();
  for (const event of localEvents) {
    localEvByKey.set(`${event.type}#${event.occurredAt}`, event);
  }
  const incomingEvByKey = new Map<string, EventRecord>();
  for (const event of payload.events) {
    incomingEvByKey.set(`${event.type}#${event.occurredAt}`, event);
  }
  const evKeys = Array.from(new Set<string>([
    ...Array.from(localEvByKey.keys()),
    ...Array.from(incomingEvByKey.keys()),
  ])).sort();
  for (const key of evKeys) {
    const local = localEvByKey.get(key);
    const incoming = incomingEvByKey.get(key);
    const sample = incoming !== undefined ? incoming : local;
    if (sample === undefined) {
      continue;
    }
    const typeName = typeNames.get(sample.type) ?? '未知事件';
    const title = `事件「${typeName}」${formatDateTimeShort(sample.occurredAt)}`;
    const localNote = local !== undefined && local.note !== null && local.note.trim().length > 0
      ? local.note : null;
    const incomingNote = incoming !== undefined && incoming.note !== null &&
      incoming.note.trim().length > 0 ? incoming.note : null;
    if (local === undefined && incoming !== undefined) {
      changes.push({
        key: `evt-${key}`,
        category: SyncCategory.EVENT,
        action: SyncAction.ADD,
        title,
        detail: '新增事件' + (incomingNote !== null ? `（${incomingNote}）` : ''),
        incomingEvent: incoming,
      });
    } else if (local !== undefined && incoming === undefined) {
      changes.push({
        key: `evt-${key}`,
        category: SyncCategory.EVENT,
        action: SyncAction.REMOVE,
        title,
        detail: '删除事件',
        localEvent: local,
      });
    } else if (local !== undefined && incoming !== undefined && localNote !== incomingNote) {
      changes.push({
        key: `evt-${key}`,
        category: SyncCategory.EVENT,
        action: SyncAction.MODIFY,
        title,
        detail: `备注 ${localNote ?? '（空）'} → ${incomingNote ?? '（空）'}`,
        localEvent: local,
        incomingEvent: incoming,
      });
    }
  }

  // ---- 照片 ----
  const localPhotoByDay = new Map<number, SyncPhotoRef>();
  for (const photo of localPhotos) {
    localPhotoByDay.set(photo.epochDay, photo);
  }
  const incomingPhotoByDay = new Map<number, SyncPhotoRef>();
  for (const photo of payload.photos) {
    incomingPhotoByDay.set(photo.epochDay, photo);
  }
  const photoDays = Array.from(new Set<number>([
    ...Array.from(localPhotoByDay.keys()),
    ...Array.from(incomingPhotoByDay.keys()),
  ])).sort((a, b) => a - b);
  for (const epochDay of photoDays) {
    const local = localPhotoByDay.get(epochDay);
    const incoming = incomingPhotoByDay.get(epochDay);
    const title = `照片 ${formatDayOfEpochDay(epochDay)}`;
    if (local === undefined && incoming !== undefined) {
      changes.push({
        key: `photo-${epochDay}`,
        category: SyncCategory.PHOTO,
        action: SyncAction.ADD,
        title,
        detail: '新增照片',
        incomingPhoto: incoming,
      });
    } else if (local !== undefined && incoming === undefined) {
      changes.push({
        key: `photo-${epochDay}`,
        category: SyncCategory.PHOTO,
        action: SyncAction.REMOVE,
        title,
        detail: '删除照片',
        localPhotoDay: epochDay,
      });
    } else if (local !== undefined && incoming !== undefined &&
      (local.fingerprint !== incoming.fingerprint ||
        (local.videoFingerprint ?? '') !== (incoming.videoFingerprint ?? ''))) {
      changes.push({
        key: `photo-${epochDay}`,
        category: SyncCategory.PHOTO,
        action: SyncAction.MODIFY,
        title,
        detail: '照片内容被替换',
        localPhotoDay: epochDay,
        incomingPhoto: incoming,
      });
    }
  }

  // ---- 随机数 ----
  const localNumByDay = new Map<number, DailyRandomNumber>();
  for (const entry of localNumbers) {
    localNumByDay.set(entry.epochDay, entry);
  }
  const incomingNumByDay = new Map<number, DailyRandomNumber>();
  for (const entry of payload.randomNumbers) {
    incomingNumByDay.set(entry.epochDay, entry);
  }
  const numDays = Array.from(new Set<number>([
    ...Array.from(localNumByDay.keys()),
    ...Array.from(incomingNumByDay.keys()),
  ])).sort((a, b) => a - b);
  for (const epochDay of numDays) {
    const local = localNumByDay.get(epochDay);
    const incoming = incomingNumByDay.get(epochDay);
    const title = `随机数 ${formatDayOfEpochDay(epochDay)}`;
    if (local === undefined && incoming !== undefined) {
      changes.push({
        key: `num-${epochDay}`,
        category: SyncCategory.RANDOM_NUMBER,
        action: SyncAction.ADD,
        title,
        detail: `新增号码 ${incoming.number}`,
        incomingNumber: incoming,
      });
    } else if (local !== undefined && incoming === undefined) {
      changes.push({
        key: `num-${epochDay}`,
        category: SyncCategory.RANDOM_NUMBER,
        action: SyncAction.REMOVE,
        title,
        detail: `删除号码 ${local.number}`,
        localNumber: local,
      });
    } else if (local !== undefined && incoming !== undefined && local.number !== incoming.number) {
      changes.push({
        key: `num-${epochDay}`,
        category: SyncCategory.RANDOM_NUMBER,
        action: SyncAction.MODIFY,
        title,
        detail: `号码 ${local.number} → ${incoming.number}`,
        localNumber: local,
        incomingNumber: incoming,
      });
    }
  }

  return changes;
}

/** 导入预览的默认勾选：只勾新增与修改；删除本机数据需用户主动勾选。 */
export function defaultSelectedSyncKeys(changes: SyncChange[]): Set<string> {
  const keys = new Set<string>();
  for (const change of changes) {
    if (change.action !== SyncAction.REMOVE) {
      keys.add(change.key);
    }
  }
  return keys;
}
