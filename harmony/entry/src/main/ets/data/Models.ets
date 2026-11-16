/**
 * 数据模型。字段名与 Android 版 Room 实体一一对应，
 * 这样 JSON 备份与同步包的字段可以直接互通。
 */

export const DEFAULT_PERSON_NAME = '我';

/** 独立的计时对象。每个人物拥有自己的佩戴状态、分段与事件。 */
export interface Person {
  id: number;
  name: string;
  createdAt: number;
}

/** 一次「戴上 → 取下」的连续时间。endAt 为 null 表示仍在佩戴中。 */
export interface WearSegment {
  id: number;
  personId: number;
  startAt: number;
  endAt: number | null;
}

/** 事件的分类。内置类型使用固定 typeKey（locked / unlocked），可改名改色但不能删除。 */
export interface EventTypeEntity {
  id: number;
  typeKey: string;
  name: string;
  /** 0xAARRGGBB */
  colorArgb: number;
  sortOrder: number;
  builtIn: boolean;
}

/** 某一事件类型的一次发生。 */
export interface EventRecord {
  id: number;
  personId: number;
  type: string;
  occurredAt: number;
  note: string | null;
}

/** 某个人物某一天的照片，作为月历格子的背景。 */
export interface DailyPhoto {
  personId: number;
  epochDay: number;
  jpeg: Uint8Array;
  /** 实况图的视频片段；普通照片为 null。 */
  video: Uint8Array | null;
  updatedAt: number;
  /** 照片内容指纹，写入时算好，同步比对时无需再读整张照片。 */
  fingerprint: string;
  videoFingerprint: string;
}

/** 照片元信息（不含 JPEG 字节）。 */
export interface DailyPhotoMeta {
  epochDay: number;
  updatedAt: number;
  fingerprint: string;
  byteSize: number;
  videoByteSize: number;
  videoFingerprint: string;
}

/** 每个「人物 + 日期」固定一个 5 位随机数。同一天不同人物的号码不允许相同。 */
export interface DailyRandomNumber {
  personId: number;
  epochDay: number;
  number: string;
  createdAt: number;
}

/**
 * 人物与同步身份的绑定。
 * 佩戴者端：本人物的身份码与盐（code 为明文，仅存本机，zip 内只放哈希）。
 * 掌管者端：绑定导入来源的身份，code 为首次验证时输入的明文，用于后续解密。
 */
export interface SyncIdentity {
  identityId: string;
  personId: number;
  displayName: string;
  code: string;
  codeSalt: string;
  createdAt: number;
  lastExportedAt: number | null;
}

export class DefaultEventTypes {
  static readonly LOCKED_KEY = 'locked';
  static readonly UNLOCKED_KEY = 'unlocked';
  static readonly LOCKED_NAME = '锁射';
  static readonly UNLOCKED_NAME = '开锁射精';

  static builtIns(): EventTypeEntity[] {
    return [
      {
        id: 0,
        typeKey: DefaultEventTypes.LOCKED_KEY,
        name: DefaultEventTypes.LOCKED_NAME,
        colorArgb: 0xFF2A78D6,
        sortOrder: 0,
        builtIn: true,
      },
      {
        id: 0,
        typeKey: DefaultEventTypes.UNLOCKED_KEY,
        name: DefaultEventTypes.UNLOCKED_NAME,
        colorArgb: 0xFFEB6834,
        sortOrder: 1,
        builtIn: true,
      },
    ];
  }

  static builtInKeys(): string[] {
    return [DefaultEventTypes.LOCKED_KEY, DefaultEventTypes.UNLOCKED_KEY];
  }
}

/** 分段是否仍在佩戴中。 */
export function isActiveSegment(segment: WearSegment): boolean {
  return segment.endAt === null || segment.endAt === undefined;
}
