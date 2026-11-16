import {
  DailyRandomNumber,
  EventRecord,
  EventTypeEntity,
  WearSegment,
} from './Models';
import { FileUtil } from '../common/FileUtil';

/**
 * 掌管者同步包的明文清单与加密载荷的编解码。
 *
 * zip 结构与 Android 版完全一致，两端的包可以互相收发：
 * - 外层 zip：manifest.json（明文，含身份校验信息）+ payload.bin（加密的内层 zip）；
 * - 内层 zip：data.json（业务数据）+ photos/<epochDay>.jpg（照片）。
 */
export class SyncPackage {
  static readonly FORMAT_VERSION = 1;
  static readonly KIND = 'locker-sync';
}

export interface SyncManifest {
  identityId: string;
  identityName: string;
  codeSalt: string;
  codeHash: string;
  exportedAt: number;
  fromEpochDay: number;
  toEpochDay: number;
}

export interface SyncPhotoRef {
  epochDay: number;
  fingerprint: string;
  videoFingerprint?: string;
}

export interface SyncPayload {
  personName: string;
  eventTypes: EventTypeEntity[];
  segments: WearSegment[];
  events: EventRecord[];
  randomNumbers: DailyRandomNumber[];
  /** 照片只记录日期与内容指纹；字节在打包/应用时逐张读写。 */
  photos: SyncPhotoRef[];
}

export class SyncPackageCodec {
  static encodeManifest(manifest: SyncManifest): string {
    const identity: IdentityOut = {
      id: manifest.identityId,
      name: manifest.identityName,
      salt: manifest.codeSalt,
      codeHash: manifest.codeHash,
    };
    const window: WindowOut = {
      fromEpochDay: manifest.fromEpochDay,
      toEpochDay: manifest.toEpochDay,
    };
    const root: ManifestOut = {
      formatVersion: SyncPackage.FORMAT_VERSION,
      kind: SyncPackage.KIND,
      identity,
      exportedAt: manifest.exportedAt,
      window,
    };
    return JSON.stringify(root, null, 2);
  }

  static decodeManifest(text: string): SyncManifest {
    let root: ManifestJson;
    try {
      root = JSON.parse(text.trim()) as ManifestJson;
    } catch (err) {
      throw new Error('同步数据清单不完整：不是合法的 JSON');
    }
    if (root === null || typeof root !== 'object') {
      throw new Error('同步数据清单不完整');
    }
    if (root.kind !== SyncPackage.KIND) {
      throw new Error('不是 Locker 同步数据');
    }
    if (root.formatVersion !== SyncPackage.FORMAT_VERSION) {
      throw new Error('同步数据版本不受支持，请升级 App');
    }
    const identity = root.identity;
    const window = root.window;
    if (identity === undefined || window === undefined) {
      throw new Error('同步数据清单不完整');
    }
    const manifest: SyncManifest = {
      identityId: String(identity.id),
      identityName: String(identity.name),
      codeSalt: String(identity.salt),
      codeHash: String(identity.codeHash),
      exportedAt: Number(root.exportedAt),
      fromEpochDay: Number(window.fromEpochDay),
      toEpochDay: Number(window.toEpochDay),
    };
    return manifest;
  }

  /** data.json 内容（载荷内层 zip 中的业务数据）。 */
  static encodePayloadJson(payload: SyncPayload): Uint8Array {
    const eventTypes: EventTypeOut[] = payload.eventTypes.map(
      (type: EventTypeEntity): EventTypeOut => {
        const item: EventTypeOut = {
          typeKey: type.typeKey,
          name: type.name,
          colorArgb: type.colorArgb,
          sortOrder: type.sortOrder,
          builtIn: type.builtIn,
        };
        return item;
      });

    const sortedSegments: WearSegment[] = payload.segments
      .slice()
      .sort((a: WearSegment, b: WearSegment) => a.startAt - b.startAt);
    const segments: SegmentOut[] = sortedSegments.map((segment: WearSegment): SegmentOut => {
      const item: SegmentOut = { startAt: segment.startAt, endAt: segment.endAt };
      return item;
    });

    const sortedEvents: EventRecord[] = payload.events
      .slice()
      .sort((a: EventRecord, b: EventRecord) => a.occurredAt - b.occurredAt);
    const events: EventOut[] = sortedEvents.map((event: EventRecord): EventOut => {
      const item: EventOut = {
        type: event.type,
        occurredAt: event.occurredAt,
        note: event.note !== null && event.note.length > 0 ? event.note : null,
      };
      return item;
    });

    const sortedNumbers: DailyRandomNumber[] = payload.randomNumbers
      .slice()
      .sort((a: DailyRandomNumber, b: DailyRandomNumber) => a.epochDay - b.epochDay);
    const numbers: NumberOut[] = sortedNumbers.map(
      (entry: DailyRandomNumber): NumberOut => {
        const item: NumberOut = {
          epochDay: entry.epochDay,
          number: entry.number,
          createdAt: entry.createdAt,
        };
        return item;
      });

    const sortedPhotos: SyncPhotoRef[] = payload.photos
      .slice()
      .sort((a: SyncPhotoRef, b: SyncPhotoRef) => a.epochDay - b.epochDay);
    const photoDays: number[] = sortedPhotos.map(
      (photo: SyncPhotoRef): number => photo.epochDay);
    const photoRefs: PhotoRefOut[] = sortedPhotos.map(
      (photo: SyncPhotoRef): PhotoRefOut => {
        const item: PhotoRefOut = {
          epochDay: photo.epochDay,
          fingerprint: photo.fingerprint,
          videoFingerprint: photo.videoFingerprint,
        };
        return item;
      });

    const root: PayloadOut = {
      personName: payload.personName,
      eventTypes,
      segments,
      events,
      randomNumbers: numbers,
      photoDays,
      photoRefs,
    };
    return FileUtil.encodeUtf8(JSON.stringify(root));
  }

  static decodePayloadJson(data: Uint8Array): SyncPayload {
    let root: PayloadJson;
    try {
      root = JSON.parse(FileUtil.decodeUtf8(data)) as PayloadJson;
    } catch (err) {
      throw new Error('同步数据内容不完整：不是合法的 JSON');
    }
    if (root === null || typeof root !== 'object') {
      throw new Error('同步数据内容不完整');
    }
    const types: EventTypeJson[] = root.eventTypes ?? [];
    const rawSegments: SegmentJson[] = root.segments ?? [];
    const rawEvents: EventJson[] = root.events ?? [];
    const rawNumbers: NumberJson[] = root.randomNumbers ?? [];

    const photos: SyncPhotoRef[] = [];
    if (root.photoRefs !== undefined) {
      for (const ref of root.photoRefs) {
        const photo: SyncPhotoRef = {
          epochDay: Number(ref.epochDay),
          fingerprint: String(ref.fingerprint ?? ''),
          videoFingerprint: ref.videoFingerprint === undefined ? undefined : String(ref.videoFingerprint),
        };
        photos.push(photo);
      }
    } else if (root.photoDays !== undefined) {
      // 旧版本包只有日期，指纹留空，由读取方补算
      for (const day of root.photoDays) {
        const photo: SyncPhotoRef = { epochDay: Number(day), fingerprint: '' };
        photos.push(photo);
      }
    }

    const eventTypes: EventTypeEntity[] = types.map((type: EventTypeJson): EventTypeEntity => {
      const item: EventTypeEntity = {
        id: 0,
        typeKey: String(type.typeKey),
        name: String(type.name),
        colorArgb: Number(type.colorArgb),
        sortOrder: Number(type.sortOrder),
        builtIn: type.builtIn === true,
      };
      return item;
    });

    const segments: WearSegment[] = rawSegments.map((segment: SegmentJson): WearSegment => {
      const item: WearSegment = {
        id: 0,
        personId: 0,
        startAt: Number(segment.startAt),
        endAt: segment.endAt === null || segment.endAt === undefined
          ? null : Number(segment.endAt),
      };
      return item;
    });

    const events: EventRecord[] = rawEvents.map((event: EventJson): EventRecord => {
      const item: EventRecord = {
        id: 0,
        personId: 0,
        type: String(event.type),
        occurredAt: Number(event.occurredAt),
        note: event.note === null || event.note === undefined ? null : String(event.note),
      };
      return item;
    });

    const randomNumbers: DailyRandomNumber[] = rawNumbers.map(
      (entry: NumberJson): DailyRandomNumber => {
        const item: DailyRandomNumber = {
          personId: 0,
          epochDay: Number(entry.epochDay),
          number: String(entry.number),
          createdAt: Number(entry.createdAt),
        };
        return item;
      });

    const payload: SyncPayload = {
      personName: String(root.personName ?? ''),
      eventTypes,
      segments,
      events,
      randomNumbers,
      photos,
    };
    return payload;
  }
}

interface ManifestJson {
  formatVersion?: number;
  kind?: string;
  identity?: IdentityOut;
  exportedAt?: number;
  window?: WindowOut;
}

interface IdentityOut {
  id?: string;
  name?: string;
  salt?: string;
  codeHash?: string;
}

interface WindowOut {
  fromEpochDay?: number;
  toEpochDay?: number;
}

interface ManifestOut {
  formatVersion: number;
  kind: string;
  identity: IdentityOut;
  exportedAt: number;
  window: WindowOut;
}

interface PayloadJson {
  personName?: string;
  eventTypes?: EventTypeJson[];
  segments?: SegmentJson[];
  events?: EventJson[];
  randomNumbers?: NumberJson[];
  photoDays?: number[];
  photoRefs?: PhotoRefOut[];
}

interface EventTypeJson {
  typeKey?: string;
  name?: string;
  colorArgb?: number;
  sortOrder?: number;
  builtIn?: boolean;
}

interface EventTypeOut {
  typeKey: string;
  name: string;
  colorArgb: number;
  sortOrder: number;
  builtIn: boolean;
}

interface SegmentJson {
  startAt?: number;
  endAt?: number | null;
}

interface SegmentOut {
  startAt: number;
  endAt: number | null;
}

interface EventJson {
  type?: string;
  occurredAt?: number;
  note?: string | null;
}

interface EventOut {
  type: string;
  occurredAt: number;
  note: string | null;
}

interface NumberJson {
  epochDay?: number;
  number?: string;
  createdAt?: number;
}

interface NumberOut {
  epochDay: number;
  number: string;
  createdAt: number;
}

interface PhotoRefOut {
  epochDay: number;
  fingerprint: string;
  videoFingerprint?: string;
}

interface PayloadOut {
  personName: string;
  eventTypes: EventTypeOut[];
  segments: SegmentOut[];
  events: EventOut[];
  randomNumbers: NumberOut[];
  photoDays: number[];
  photoRefs: PhotoRefOut[];
}
