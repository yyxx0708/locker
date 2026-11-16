import { zlib } from '@kit.BasicServicesKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { FileUtil } from '../common/FileUtil';
import { SyncPackageCodec, SyncManifest, SyncPayload, SyncPhotoRef } from './SyncPackage';
import { SyncCrypto } from './SyncCrypto';
import { DailyReport } from './DailyReport';

const DOMAIN = 0x0000;

/**
 * 同步包的读写。
 *
 * 结构与 Android 版一致，两端的包可以互相收发：
 *   外层 zip = manifest.json（明文） + payload.bin（AES-GCM 加密的内层 zip）
 *   内层 zip = data.json + photos/<epochDay>.jpg
 *
 * 实现差异：鸿蒙的 zlib 只提供「整目录压缩 / 解压」，没有条目级 API，
 * 所以这里先把要打包的文件写进临时目录再整包压缩；解包则先解到临时目录，
 * 再按白名单读取 manifest.json / payload.bin / data.json / photos/*.jpg。
 * 所有临时目录都会在 finally 里删除。
 */
export class SyncArchive {
  static readonly MAX_PACKAGE_BYTES = 300 * 1024 * 1024;
  static readonly MAX_MANIFEST_BYTES = 1024 * 1024;
  static readonly MAX_DATA_JSON_BYTES = 20 * 1024 * 1024;
  static readonly MAX_PHOTOS = 1000;
  static readonly MAX_PHOTO_BYTES = 10 * 1024 * 1024;
  static readonly MAX_VIDEO_BYTES = 60 * 1024 * 1024;

  private static readonly PHOTO_NAME = /^photos\/(\d+)\.jpg$/;
  private static readonly VIDEO_NAME = /^photos\/(\d+)\.mp4$/;

  /**
   * 写出同步包。
   *
   * @param target 目标 zip 路径
   * @param workDir 临时工作目录（调用方负责在完成后清理）
   * @param readPhoto 按天读取照片字节，返回 null 表示缺失
   */
  static async writePackage(
    target: string,
    workDir: string,
    manifest: SyncManifest,
    payloadJson: Uint8Array,
    photos: SyncPhotoRef[],
    keyBytes: Uint8Array,
    readPhoto: (epochDay: number) => Promise<Uint8Array | null>,
    readVideo: (epochDay: number) => Promise<Uint8Array | null>,
    onProgress: (done: number, total: number) => void,
  ): Promise<void> {
    if (photos.length > SyncArchive.MAX_PHOTOS) {
      throw new Error('照片数量超出上限');
    }
    FileUtil.ensureDir(workDir);
    const outerDir = FileUtil.join(workDir, 'outer');
    const innerDir = FileUtil.join(workDir, 'inner');
    FileUtil.remove(outerDir);
    FileUtil.remove(innerDir);
    FileUtil.ensureDir(outerDir);
    FileUtil.ensureDir(FileUtil.join(innerDir, 'photos'));

    try {
      // 内层：data.json + 照片
      FileUtil.writeBytes(FileUtil.join(innerDir, 'data.json'), payloadJson);
      for (let index = 0; index < photos.length; index++) {
        const photo = photos[index];
        const jpeg = await readPhoto(photo.epochDay);
        if (jpeg === null) {
          throw new Error('照片数据缺失');
        }
        if (jpeg.length > SyncArchive.MAX_PHOTO_BYTES) {
          throw new Error('照片体积超出上限');
        }
        FileUtil.writeBytes(FileUtil.join(innerDir, 'photos', `${photo.epochDay}.jpg`), jpeg);
        if (photo.videoFingerprint !== undefined) {
          const video = await readVideo(photo.epochDay);
          if (video === null || video.length === 0 || video.length > SyncArchive.MAX_VIDEO_BYTES) {
            throw new Error('实况视频缺失或超过 60 MB');
          }
          FileUtil.writeBytes(FileUtil.join(innerDir, 'photos', `${photo.epochDay}.mp4`), video);
        }
        onProgress(index + 1, photos.length);
      }

      // 内层整包压缩
      const innerZip = FileUtil.join(workDir, 'inner.zip');
      FileUtil.remove(innerZip);
      await zlib.compressFile(innerDir, innerZip, {
        level: zlib.CompressLevel.COMPRESS_LEVEL_BEST_SPEED,
      });

      // 加密内层 zip：IV 前缀 + 密文（含认证标签）
      const innerBytes = FileUtil.readBytes(innerZip);
      const encrypted = SyncCrypto.encrypt(innerBytes, keyBytes);

      // 外层：manifest.json + payload.bin
      FileUtil.writeText(
        FileUtil.join(outerDir, 'manifest.json'),
        SyncPackageCodec.encodeManifest(manifest),
      );
      FileUtil.writeBytes(FileUtil.join(outerDir, 'payload.bin'), encrypted);
      FileUtil.remove(target);
      await zlib.compressFile(outerDir, target, {
        level: zlib.CompressLevel.COMPRESS_LEVEL_NO_COMPRESSION,
      });
    } finally {
      FileUtil.remove(outerDir);
      FileUtil.remove(innerDir);
      FileUtil.remove(FileUtil.join(workDir, 'inner.zip'));
    }
  }

  /** 读取明文清单。zip 解到临时目录后按名字取。 */
  static async readManifest(zipFile: string, workDir: string): Promise<SyncManifest> {
    const unpackDir = FileUtil.join(workDir, 'manifest');
    FileUtil.remove(unpackDir);
    FileUtil.ensureDir(unpackDir);
    try {
      await zlib.decompressFile(zipFile, unpackDir);
      const manifestPath = SyncArchive.findFile(unpackDir, 'manifest.json');
      if (manifestPath === null) {
        throw new Error('同步包缺少清单');
      }
      if (FileUtil.size(manifestPath) > SyncArchive.MAX_MANIFEST_BYTES) {
        throw new Error('同步包清单过大');
      }
      return SyncPackageCodec.decodeManifest(FileUtil.readText(manifestPath));
    } finally {
      FileUtil.remove(unpackDir);
    }
  }

  /** 导入解析结果：业务数据 + 照片指纹 + 解压出的内层目录。 */
  static async parseEncryptedPayload(
    zipFile: string,
    workDir: string,
    keyBytes: Uint8Array,
    onProgress: (done: number, total: number) => void,
  ): Promise<ParsedImport> {
    const outerDir = FileUtil.join(workDir, 'outer');
    const innerDir = FileUtil.join(workDir, 'inner');
    FileUtil.remove(outerDir);
    FileUtil.remove(innerDir);
    FileUtil.ensureDir(outerDir);
    FileUtil.ensureDir(innerDir);

    try {
      await zlib.decompressFile(zipFile, outerDir);
      const payloadPath = SyncArchive.findFile(outerDir, 'payload.bin');
      if (payloadPath === null) {
        throw new Error('同步包缺少加密数据');
      }
      if (FileUtil.size(payloadPath) > SyncArchive.MAX_PACKAGE_BYTES) {
        throw new Error('同步数据超出体积上限');
      }
      let decrypted: Uint8Array;
      try {
        decrypted = SyncCrypto.decrypt(FileUtil.readBytes(payloadPath), keyBytes);
      } catch (err) {
        throw new Error('验证码不正确或数据已损坏');
      }
      const innerZip = FileUtil.join(workDir, 'inner.zip');
      FileUtil.writeBytes(innerZip, decrypted);
      await zlib.decompressFile(innerZip, innerDir);
      FileUtil.remove(innerZip);

      const dataPath = SyncArchive.findFile(innerDir, 'data.json');
      if (dataPath === null) {
        throw new Error('同步数据缺少内容');
      }
      if (FileUtil.size(dataPath) > SyncArchive.MAX_DATA_JSON_BYTES) {
        throw new Error('同步数据超出体积上限');
      }
      const payload = SyncPackageCodec.decodePayloadJson(FileUtil.readBytes(dataPath));
      if (payload.photos.length > SyncArchive.MAX_PHOTOS) {
        throw new Error('照片数量超出上限');
      }
      onProgress(payload.photos.length, payload.photos.length);

      const photos = SyncArchive.resolvePhotoFingerprints(innerDir, payload);
      const merged: SyncPayload = {
        personName: payload.personName,
        eventTypes: payload.eventTypes,
        segments: payload.segments,
        events: payload.events,
        randomNumbers: payload.randomNumbers,
        photos,
      };
      const result: ParsedImport = { payload: merged, innerDir, photos };
      return result;
    } finally {
      FileUtil.remove(outerDir);
    }
  }

  /**
   * 新版本在 data.json 里附带指纹，校验阶段无需读取照片内容；
   * 旧版本只有日期，逐张读取补算。
   */
  private static resolvePhotoFingerprints(
    innerDir: string,
    payload: SyncPayload,
  ): SyncPhotoRef[] {
    const resolved: SyncPhotoRef[] = [];
    for (const ref of payload.photos) {
      if (ref.fingerprint.length > 0) {
        resolved.push(ref);
        continue;
      }
      const path = FileUtil.join(innerDir, 'photos', `${ref.epochDay}.jpg`);
      if (!FileUtil.exists(path)) {
        throw new Error('同步数据缺少照片');
      }
      const jpeg = FileUtil.readBytes(path);
      resolved.push({ epochDay: ref.epochDay, fingerprint: DailyReport.photoFingerprint(jpeg),
        videoFingerprint: ref.videoFingerprint });
    }
    return resolved;
  }

  /**
   * 核对同步包完整性：清单声明的照片必须都有对应文件，且不夹带多余照片。
   */
  static verifyPhotoEntries(innerDir: string, payload: SyncPayload): boolean {
    try {
      const expected = new Set<number>();
      const expectedVideos = new Set<number>();
      for (const photo of payload.photos) {
        expected.add(photo.epochDay);
        if (photo.videoFingerprint !== undefined) {
          expectedVideos.add(photo.epochDay);
        }
      }
      const photosDir = FileUtil.join(innerDir, 'photos');
      if (expected.size === 0) {
        return FileUtil.listFiles(photosDir).length === 0;
      }
      const actual = new Set<number>();
      const actualVideos = new Set<number>();
      for (const name of FileUtil.listFiles(photosDir)) {
        const match = SyncArchive.PHOTO_NAME.exec(`photos/${name}`);
        if (match !== null) {
          actual.add(Number(match[1]));
        } else {
          const videoMatch = SyncArchive.VIDEO_NAME.exec(`photos/${name}`);
          if (videoMatch === null) {
            return false;
          }
          actualVideos.add(Number(videoMatch[1]));
        }
      }
      if (actual.size !== expected.size || actualVideos.size !== expectedVideos.size) {
        return false;
      }
      for (const day of expected) {
        if (!actual.has(day)) {
          return false;
        }
      }
      for (const day of expectedVideos) {
        if (!actualVideos.has(day)) {
          return false;
        }
        const videoPath = FileUtil.join(photosDir, `${day}.mp4`);
        const size = FileUtil.size(videoPath);
        if (size === 0 || size > SyncArchive.MAX_VIDEO_BYTES) {
          return false;
        }
        const ref = payload.photos.find((photo: SyncPhotoRef) => photo.epochDay === day);
        if (ref === undefined || DailyReport.photoFingerprint(FileUtil.readBytes(videoPath)) !== ref.videoFingerprint) {
          return false;
        }
      }
      for (const day of actualVideos) {
        if (!expectedVideos.has(day)) {
          return false;
        }
      }
      return true;
    } catch (err) {
      return false;
    }
  }

  /** 按天读取已解压内层目录中的照片。 */
  static readPhoto(innerDir: string, epochDay: number): Uint8Array | null {
    const path = FileUtil.join(innerDir, 'photos', `${epochDay}.jpg`);
    if (!FileUtil.exists(path)) {
      return null;
    }
    return FileUtil.readBytes(path);
  }

  static readVideo(innerDir: string, epochDay: number): Uint8Array | null {
    const path = FileUtil.join(innerDir, 'photos', `${epochDay}.mp4`);
    return FileUtil.exists(path) ? FileUtil.readBytes(path) : null;
  }

  /** zlib 解压后可能多包一层同名目录，这里递归找目标文件。 */
  private static findFile(root: string, name: string, depth: number = 0): string | null {
    if (depth > 4) {
      return null;
    }
    const direct = FileUtil.join(root, name);
    if (FileUtil.exists(direct)) {
      return direct;
    }
    for (const child of FileUtil.listFiles(root)) {
      const path = FileUtil.join(root, child);
      try {
        if (fs.statSync(path).isDirectory()) {
          const found = SyncArchive.findFile(path, name, depth + 1);
          if (found !== null) {
            return found;
          }
        }
      } catch (err) {
        hilog.warn(DOMAIN, 'Locker', 'stat failed: %{public}s', path);
      }
    }
    return null;
  }
}

export interface ParsedImport {
  payload: SyncPayload;
  innerDir: string;
  photos: SyncPhotoRef[];
}
