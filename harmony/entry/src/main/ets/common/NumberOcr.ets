import { textRecognition } from '@kit.CoreVisionKit';
import { image } from '@kit.ImageKit';
import { DailyReport } from '../data/DailyReport';
import { PhotoProcessor } from './PhotoProcessor';

/**
 * 离线号码识别。
 *
 * 与 Android 版一致：识别照片中的文字，再从文字里抽取 5 位数字候选，
 * 用于「上传后检测随机数」和日详情里的「识别照片号码」。
 * 鸿蒙的 CoreVisionKit 提供端侧 OCR，不联网。
 *
 * 同一张照片只识别一次：按 JPEG 内容指纹做缓存。
 */
const CACHE_LIMIT = 32;
const cache = new Map<string, string[]>();

/** 识别照片中的 5 位号码候选；识别失败返回空数组。 */
export async function recognizeNumberCandidates(jpeg: Uint8Array): Promise<string[]> {
  const key = DailyReport.photoFingerprint(jpeg);
  const cached = cache.get(key);
  if (cached !== undefined) {
    return cached;
  }
  const pixelMap = await PhotoProcessor.decode(jpeg);
  if (pixelMap === null) {
    return [];
  }
  try {
    const result = await textRecognition.recognizeText({ pixelMap });
    const candidates = DailyReport.findFiveDigitCandidates(result.value ?? '');
    if (cache.size >= CACHE_LIMIT) {
      const keys: string[] = Array.from(cache.keys());
      if (keys.length > 0) {
        cache.delete(keys[0]);
      }
    }
    cache.set(key, candidates);
    return candidates;
  } catch (err) {
    return [];
  } finally {
    await pixelMap.release();
  }
}

/** 识别整段文字，供需要展示原文的场景使用。 */
export async function recognizeText(pixelMap: image.PixelMap): Promise<string> {
  try {
    const result = await textRecognition.recognizeText({ pixelMap });
    return result.value ?? '';
  } catch (err) {
    return '';
  }
}
