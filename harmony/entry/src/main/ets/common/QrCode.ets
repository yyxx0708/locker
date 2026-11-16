import { detectBarcode, generateBarcode, scanCore, scanBarcode } from '@kit.ScanKit';
import { image } from '@kit.ImageKit';
import { fileUri } from '@kit.CoreFileKit';
import { FileUtil } from './FileUtil';

/**
 * 二维码生成与识别。
 *
 * 生成用 ScanKit 的 generateBarcode；识别用 detectBarcode.decode（从图片 URI 解码）。
 * 两条路径都是端侧能力，不联网、不需要相机权限，与 Android 版的
 * 「生成二维码 + 从相册选图解码」流程对应。
 *
 * 注意 detectBarcode.decode 只接受图片 URI（不接受 PixelMap），
 * 所以解码前先把字节落到缓存文件，再传 file:// URI。
 */

/** 生成二维码 PixelMap（调用方负责 release）。 */
export async function generateQrPixelMap(
  content: string,
  sizePx: number,
): Promise<image.PixelMap | null> {
  try {
    return await generateBarcode.createBarcode(content, {
      scanType: scanCore.ScanType.QR_CODE,
      width: Math.max(200, Math.min(4096, sizePx)),
      height: Math.max(200, Math.min(4096, sizePx)),
      margin: 4,
      level: generateBarcode.ErrorCorrectionLevel.LEVEL_M,
    });
  } catch (err) {
    return null;
  }
}

/** 生成二维码并编码为 PNG 字节，供分享卡等需要位图的场景。 */
export async function generateQrPng(
  content: string,
  sizePx: number,
): Promise<Uint8Array | null> {
  const pixelMap = await generateQrPixelMap(content, sizePx);
  if (pixelMap === null) {
    return null;
  }
  try {
    const packer = image.createImagePacker();
    try {
      const buffer = await packer.packToData(pixelMap, { format: 'image/png', quality: 100 });
      return new Uint8Array(buffer);
    } finally {
      await packer.release();
    }
  } catch (err) {
    return null;
  } finally {
    await pixelMap.release();
  }
}

/** 从图片文件路径解码二维码。 */
export async function decodeQrFromFile(path: string): Promise<string | null> {
  const input: detectBarcode.InputImage = { uri: fileUri.getUriFromPath(path) };
  return decodeWithInput(input);
}

/**
 * 从图片字节解码二维码。
 * 先把字节写到缓存文件再走 URI 解码，因为 ScanKit 的图片解码只接受 URI。
 */
export async function decodeQrFromBytes(
  bytes: Uint8Array,
  cacheDir: string,
): Promise<string | null> {
  const dir = FileUtil.join(cacheDir, 'qr');
  FileUtil.ensureDir(dir);
  const path = FileUtil.join(dir, `scan-${Date.now()}.jpg`);
  FileUtil.writeBytes(path, bytes);
  try {
    return await decodeQrFromFile(path);
  } finally {
    FileUtil.remove(path);
  }
}

async function decodeWithInput(input: detectBarcode.InputImage): Promise<string | null> {
  try {
    const options: scanBarcode.ScanOptions = {
      scanTypes: [scanCore.ScanType.QR_CODE],
      enableMultiMode: true,
    };
    const results: scanBarcode.ScanResult[] = await detectBarcode.decode(input, options);
    for (const result of results) {
      if (result.originalValue !== undefined && result.originalValue.length > 0) {
        return result.originalValue;
      }
    }
    return null;
  } catch (err) {
    return null;
  }
}
