import {
  DailyRandomNumber,
  DefaultEventTypes,
  EventRecord,
  EventTypeEntity,
  Person,
  WearSegment,
} from './Models';
import { BackupData } from './LockerRepository';

const SCHEMA_VERSION = 4;

/**
 * JSON 备份的编解码。
 *
 * 格式与 Android 版 schemaVersion 4 完全一致，可以双向导入导出：
 * - 顶层字段：schemaVersion / exportedAt / persons / eventTypes / segments / events / dailyRandomNumbers
 * - 排序规则也一致（人物按 id，类型按 sortOrder，分段与事件按时间，随机数按日期+人物），
 *   这样两端导出的文件除了时间戳之外逐行相同，便于人工比对。
 * - 旧版本备份（v1–v3）同样可读：v1/v2 没有事件类型，统一用内置两项；
 *   v1 没有人物，全部记录归入默认人物。
 */
export class BackupJson {
  static encode(data: BackupData): string {
    const persons: PersonOut[] = data.persons
      .slice()
      .sort((a, b) => a.id - b.id)
      .map((p): PersonOut => ({ id: p.id, name: p.name, createdAt: p.createdAt }));

    const eventTypes: EventTypeOut[] = data.eventTypes
      .slice()
      .sort((a, b) => (a.sortOrder - b.sortOrder) || (a.id - b.id))
      .map((t): EventTypeOut => {
        const item: EventTypeOut = {
          typeKey: t.typeKey,
          name: t.name,
          colorArgb: t.colorArgb,
          sortOrder: t.sortOrder,
          builtIn: t.builtIn,
        };
        return item;
      });

    const segments: SegmentOut[] = data.segments
      .slice()
      .sort((a, b) => a.startAt - b.startAt)
      .map((s): SegmentOut => {
        const item: SegmentOut = {
          personId: s.personId,
          startAt: s.startAt,
          endAt: s.endAt,
        };
        return item;
      });

    const events: EventOut[] = data.events
      .slice()
      .sort((a, b) => a.occurredAt - b.occurredAt)
      .map((e): EventOut => {
        const item: EventOut = {
          personId: e.personId,
          type: e.type,
          occurredAt: e.occurredAt,
          note: e.note !== null && e.note.length > 0 ? e.note : null,
        };
        return item;
      });

    const numbers: RandomNumberOut[] = data.dailyRandomNumbers
      .slice()
      .sort((a, b) => (a.epochDay - b.epochDay) || (a.personId - b.personId))
      .map((n): RandomNumberOut => {
        const item: RandomNumberOut = {
          personId: n.personId,
          epochDay: n.epochDay,
          number: n.number,
          createdAt: n.createdAt,
        };
        return item;
      });

    const root: BackupRootOut = {
      schemaVersion: SCHEMA_VERSION,
      exportedAt: Date.now(),
      persons,
      eventTypes,
      segments,
      events,
      dailyRandomNumbers: numbers,
    };
    return JSON.stringify(root, null, 2);
  }

  static decode(text: string): BackupData {
    let root: BackupRoot;
    try {
      root = JSON.parse(text.trim().replace(/^﻿/, '')) as BackupRoot;
    } catch (err) {
      throw new Error('备份格式不完整：不是合法的 JSON 文件');
    }
    if (root === null || typeof root !== 'object') {
      throw new Error('备份格式不完整');
    }
    const version = root.schemaVersion ?? -1;
    switch (version) {
      case SCHEMA_VERSION:
        return BackupJson.decodeV4(root);
      case 3:
        return BackupJson.decodeV3(root);
      case 2:
        return BackupJson.decodeV2(root);
      case 1:
        return BackupJson.decodeV1(root);
      default:
        throw new Error(`不支持的备份版本：${version}`);
    }
  }

  private static decodeV4(root: BackupRoot): BackupData {
    const exportedAt = root.exportedAt ?? Date.now();
    const persons = BackupJson.parsePersons(root, exportedAt);
    const eventTypes = BackupJson.parseEventTypes(root);
    const segments = BackupJson.parseSegments(root, null);
    const events = BackupJson.parseEvents(root, null);
    const numbers = BackupJson.parseDailyRandomNumbers(root);
    BackupJson.validate(persons, eventTypes, segments, events, numbers);
    const data: BackupData = { persons, eventTypes, segments, events, dailyRandomNumbers: numbers };
    return data;
  }

  private static decodeV3(root: BackupRoot): BackupData {
    const exportedAt = root.exportedAt ?? Date.now();
    const persons = BackupJson.parsePersons(root, exportedAt);
    const eventTypes = BackupJson.parseEventTypes(root);
    const segments = BackupJson.parseSegments(root, null);
    const events = BackupJson.parseEvents(root, null);
    BackupJson.validate(persons, eventTypes, segments, events, []);
    const data: BackupData = { persons, eventTypes, segments, events, dailyRandomNumbers: [] };
    return data;
  }

  private static decodeV2(root: BackupRoot): BackupData {
    const exportedAt = root.exportedAt ?? Date.now();
    const persons = BackupJson.parsePersons(root, exportedAt);
    const eventTypes = DefaultEventTypes.builtIns();
    const segments = BackupJson.parseSegments(root, null);
    const events = BackupJson.parseEvents(root, null);
    BackupJson.validate(persons, eventTypes, segments, events, []);
    const data: BackupData = { persons, eventTypes, segments, events, dailyRandomNumbers: [] };
    return data;
  }

  private static decodeV1(root: BackupRoot): BackupData {
    const exportedAt = root.exportedAt ?? Date.now();
    const segments = BackupJson.parseSegments(root, 1);
    const events = BackupJson.parseEvents(root, 1);
    let createdAt = exportedAt;
    for (const segment of segments) {
      createdAt = Math.min(createdAt, segment.startAt);
    }
    for (const event of events) {
      createdAt = Math.min(createdAt, event.occurredAt);
    }
    const persons: Person[] = [{ id: 1, name: '我', createdAt }];
    const eventTypes = DefaultEventTypes.builtIns();
    BackupJson.validate(persons, eventTypes, segments, events, []);
    const data: BackupData = { persons, eventTypes, segments, events, dailyRandomNumbers: [] };
    return data;
  }

  private static parsePersons(root: BackupRoot, exportedAt: number): Person[] {
    const items = root.persons;
    if (!Array.isArray(items) || items.length === 0) {
      throw new Error('备份中没有人物');
    }
    const persons: Person[] = [];
    for (const item of items) {
      const id = Number(item.id);
      const name = String(item.name ?? '').trim();
      if (!Number.isFinite(id) || id <= 0) {
        throw new Error('备份中的人物 id 无效');
      }
      if (name.length === 0) {
        throw new Error('备份中存在空名称的人物');
      }
      if (name.length > 20) {
        throw new Error('备份中的人物名称过长');
      }
      persons.push({ id, name, createdAt: Number(item.createdAt ?? exportedAt) });
    }
    const ids = persons.map((p: Person) => p.id);
    if (new Set(ids).size !== ids.length) {
      throw new Error('备份中存在重复的人物 id');
    }
    const names = persons.map((p: Person) => p.name);
    if (new Set(names).size !== names.length) {
      throw new Error('备份中存在同名人物');
    }
    return persons;
  }

  private static parseEventTypes(root: BackupRoot): EventTypeEntity[] {
    const items = root.eventTypes;
    if (!Array.isArray(items) || items.length === 0) {
      throw new Error('备份中没有事件类型');
    }
    const types: EventTypeEntity[] = [];
    let index = 0;
    for (const item of items) {
      const typeKey = String(item.typeKey ?? '').trim();
      const name = String(item.name ?? '').trim();
      const builtIn = item.builtIn === true;
      if (typeKey.length === 0) {
        throw new Error('备份中存在空标识的事件类型');
      }
      if (name.length === 0) {
        throw new Error('备份中存在空名称的事件类型');
      }
      if (name.length > 20) {
        throw new Error('备份中的事件名称过长');
      }
      const shouldBeBuiltIn = DefaultEventTypes.builtInKeys().indexOf(typeKey) >= 0;
      if (builtIn !== shouldBeBuiltIn) {
        throw new Error(`备份中的事件类型标记无效：${typeKey}`);
      }
      types.push({
        id: 0,
        typeKey,
        name,
        colorArgb: (Number(item.colorArgb) | 0xFF000000) >>> 0,
        sortOrder: Number(item.sortOrder ?? index),
        builtIn,
      });
      index++;
    }
    const keys = types.map((t: EventTypeEntity) => t.typeKey);
    if (new Set(keys).size !== keys.length) {
      throw new Error('备份中存在重复的事件类型标识');
    }
    const names = types.map((t: EventTypeEntity) => t.name);
    if (new Set(names).size !== names.length) {
      throw new Error('备份中存在同名事件类型');
    }
    for (const key of DefaultEventTypes.builtInKeys()) {
      if (keys.indexOf(key) < 0) {
        throw new Error('备份缺少内置事件类型');
      }
    }
    return types;
  }

  private static parseSegments(root: BackupRoot, defaultPersonId: number | null): WearSegment[] {
    const items = root.segments;
    if (!Array.isArray(items)) {
      throw new Error('备份格式不完整：缺少分段');
    }
    const segments: WearSegment[] = [];
    for (const item of items) {
      segments.push({
        id: 0,
        personId: defaultPersonId ?? Number(item.personId),
        startAt: Number(item.startAt),
        endAt: item.endAt === null || item.endAt === undefined ? null : Number(item.endAt),
      });
    }
    return segments;
  }

  private static parseEvents(root: BackupRoot, defaultPersonId: number | null): EventRecord[] {
    const items = root.events;
    if (!Array.isArray(items)) {
      throw new Error('备份格式不完整：缺少事件');
    }
    const events: EventRecord[] = [];
    for (const item of items) {
      const note = item.note === null || item.note === undefined ? '' : String(item.note);
      events.push({
        id: 0,
        personId: defaultPersonId ?? Number(item.personId),
        type: String(item.type),
        occurredAt: Number(item.occurredAt),
        note: note.trim().length > 0 ? note : null,
      });
    }
    return events;
  }

  private static parseDailyRandomNumbers(root: BackupRoot): DailyRandomNumber[] {
    const items = root.dailyRandomNumbers;
    if (!Array.isArray(items)) {
      throw new Error('备份格式不完整：缺少每日随机数');
    }
    const numbers: DailyRandomNumber[] = [];
    for (const item of items) {
      const number = String(item.number ?? '');
      if (!/^\d{5}$/.test(number)) {
        throw new Error('备份中存在无效的每日随机数');
      }
      const value = Number(number);
      if (value < 10_000 || value > 99_999) {
        throw new Error('备份中的每日随机数超出范围');
      }
      numbers.push({
        personId: Number(item.personId),
        epochDay: Number(item.epochDay),
        number,
        createdAt: Number(item.createdAt ?? 0),
      });
    }
    const keys = numbers.map((n: DailyRandomNumber) => `${n.personId}#${n.epochDay}`);
    if (new Set(keys).size !== keys.length) {
      throw new Error('备份中存在重复的每日随机数');
    }
    const byDay = new Map<number, string[]>();
    for (const entry of numbers) {
      const list = byDay.get(entry.epochDay) ?? [];
      list.push(entry.number);
      byDay.set(entry.epochDay, list);
    }
    byDay.forEach((list) => {
      if (new Set(list).size !== list.length) {
        throw new Error('备份中同一天不同人物的随机数重复');
      }
    });
    return numbers;
  }

  private static validate(
    persons: Person[],
    eventTypes: EventTypeEntity[],
    segments: WearSegment[],
    events: EventRecord[],
    numbers: DailyRandomNumber[],
  ): void {
    const personIds = new Set(persons.map((p: Person) => p.id));
    const typeKeys = new Set(eventTypes.map((t: EventTypeEntity) => t.typeKey));
    for (const segment of segments) {
      if (!personIds.has(segment.personId)) {
        throw new Error('备份中有分段引用了不存在的人物');
      }
    }
    for (const event of events) {
      if (!personIds.has(event.personId)) {
        throw new Error('备份中有事件引用了不存在的人物');
      }
      if (!typeKeys.has(event.type)) {
        throw new Error('备份中有事件引用了不存在的事件类型');
      }
    }
    for (const entry of numbers) {
      if (!personIds.has(entry.personId)) {
        throw new Error('备份中有每日随机数引用了不存在的人物');
      }
    }

    const byPerson = new Map<number, WearSegment[]>();
    for (const segment of segments) {
      const list = byPerson.get(segment.personId) ?? [];
      list.push(segment);
      byPerson.set(segment.personId, list);
    }
    byPerson.forEach((list) => BackupJson.validateSegments(list));
  }

  private static validateSegments(segments: WearSegment[]): void {
    const sorted = segments.slice().sort((a, b) => a.startAt - b.startAt);
    let activeCount = 0;
    for (const segment of sorted) {
      if (segment.endAt === null) {
        activeCount++;
      }
    }
    if (activeCount > 1) {
      throw new Error('备份中同一人物同时存在多个佩戴中的分段');
    }
    let previousEnd = Number.MIN_SAFE_INTEGER;
    for (const segment of sorted) {
      if (segment.endAt !== null && segment.endAt < segment.startAt) {
        throw new Error('备份中存在结束时间早于开始时间的分段');
      }
      if (segment.startAt < previousEnd) {
        throw new Error('备份中同一人物的分段存在重叠');
      }
      previousEnd = segment.endAt === null ? Number.MAX_SAFE_INTEGER : segment.endAt;
    }
  }
}

/** 解析用到的宽松结构，字段全部可选，便于兼容旧版本。 */
interface BackupRoot {
  schemaVersion?: number;
  exportedAt?: number;
  persons?: PersonJson[];
  eventTypes?: EventTypeJson[];
  segments?: SegmentJson[];
  events?: EventJson[];
  dailyRandomNumbers?: RandomNumberJson[];
}

interface PersonJson {
  id?: number;
  name?: string;
  createdAt?: number;
}

interface EventTypeJson {
  typeKey?: string;
  name?: string;
  colorArgb?: number;
  sortOrder?: number;
  builtIn?: boolean;
}

interface SegmentJson {
  personId?: number;
  startAt?: number;
  endAt?: number | null;
}

interface EventJson {
  personId?: number;
  type?: string;
  occurredAt?: number;
  note?: string | null;
}

interface RandomNumberJson {
  personId?: number;
  epochDay?: number;
  number?: string;
  createdAt?: number;
}

interface PersonOut {
  id: number;
  name: string;
  createdAt: number;
}

interface EventTypeOut {
  typeKey: string;
  name: string;
  colorArgb: number;
  sortOrder: number;
  builtIn: boolean;
}

interface SegmentOut {
  personId: number;
  startAt: number;
  endAt: number | null;
}

interface EventOut {
  personId: number;
  type: string;
  occurredAt: number;
  note: string | null;
}

interface RandomNumberOut {
  personId: number;
  epochDay: number;
  number: string;
  createdAt: number;
}

interface BackupRootOut {
  schemaVersion: number;
  exportedAt: number;
  persons: PersonOut[];
  eventTypes: EventTypeOut[];
  segments: SegmentOut[];
  events: EventOut[];
  dailyRandomNumbers: RandomNumberOut[];
}
