import { image } from '@kit.ImageKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { FileUtil } from './FileUtil';
import { formatDateTime } from './TimeFormat';

/**
 * 图片读取与压缩。
 *
 * 与 Android 版的 loadCompressedJpeg 对齐：
 * 按 EXIF 方向校正 → 缩放到最长边不超过 1024 → 可选烧录时间水印 → 压成 JPEG(q80)。
 *
 * 鸿蒙的 ImageSource 可以直接按 desiredSize 解码，省掉 Android 那套 inSampleSize 计算；
 * 旋转交给 PixelMap 的 rotate，避免手工重采样导致画质二次损失。
 */
export interface CompressOptions {
  maxDimension?: number;
  quality?: number;
  /** 不为 null 时在右下角烧录该时间戳。 */
  watermarkAt?: number | null;
}

export class PhotoProcessor {
  /** 从文件路径读取并压缩为 JPEG 字节；失败返回 null。 */
  static async loadCompressedJpeg(uri: string, options: CompressOptions): Promise<Uint8Array | null> {
    const maxDimension = options.maxDimension ?? 1024;
    const quality = options.quality ?? 80;
    try {
      const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      const source = image.createImageSource(file.fd);
      try {
        const info = await source.getImageInfo();
        let orientation = '';
        try {
          orientation = source.getImagePropertySync(image.PropertyKey.ORIENTATION);
        } catch (exifErr) {
          // 没有 EXIF 的图片按正常方向处理
          orientation = '';
        }
        const rotate = PhotoProcessor.exifRotation(orientation);
        const longest = Math.max(info.size.width, info.size.height);
        const scale = longest > maxDimension ? maxDimension / longest : 1;
        const desiredWidth = Math.max(1, Math.round(info.size.width * scale));
        const desiredHeight = Math.max(1, Math.round(info.size.height * scale));
        let pixelMap = await source.createPixelMap({
          desiredSize: { width: desiredWidth, height: desiredHeight },
          desiredPixelFormat: image.PixelMapFormat.RGBA_8888,
        });
        try {
          if (rotate !== 0) {
            await pixelMap.rotate(rotate);
          }
          if (options.watermarkAt !== undefined && options.watermarkAt !== null) {
            await PhotoProcessor.drawWatermark(pixelMap, options.watermarkAt);
          }
          return await PhotoProcessor.encodeJpeg(pixelMap, quality);
        } finally {
          await pixelMap.release();
        }
      } finally {
        await source.release();
        fs.closeSync(file);
      }
    } catch (err) {
      return null;
    }
  }

  /** 把 PixelMap 压成 JPEG 字节。 */
  static async encodeJpeg(pixelMap: image.PixelMap, quality: number): Promise<Uint8Array> {
    const packer = image.createImagePacker();
    try {
      const buffer = await packer.packToData(pixelMap, {
        format: 'image/jpeg',
        quality,
      });
      return new Uint8Array(buffer);
    } finally {
      await packer.release();
    }
  }

  /** 从 JPEG 字节解码出 PixelMap（调用方负责 release）。 */
  static async decode(jpeg: Uint8Array): Promise<image.PixelMap | null> {
    let source: image.ImageSource | null = null;
    try {
      source = image.createImageSource(jpeg.buffer.slice(
        jpeg.byteOffset,
        jpeg.byteOffset + jpeg.byteLength,
      ));
      // 先完整解码再释放 ImageSource，否则拿到的可能是空图
      const pixelMap = await source.createPixelMap({
        desiredPixelFormat: image.PixelMapFormat.RGBA_8888,
      });
      return pixelMap;
    } catch (err) {
      return null;
    } finally {
      if (source !== null) {
        try {
          await source.release();
        } catch (releaseErr) {
          // 释放失败不影响已解码的 PixelMap
        }
      }
    }
  }

  /** 解码并缩放到指定最长边，用于月历缩略图。 */
  static async decodeScaled(jpeg: Uint8Array, maxDimension: number): Promise<image.PixelMap | null> {
    let source: image.ImageSource | null = null;
    try {
      source = image.createImageSource(jpeg.buffer.slice(
        jpeg.byteOffset,
        jpeg.byteOffset + jpeg.byteLength,
      ));
      const info = await source.getImageInfo();
      const longest = Math.max(info.size.width, info.size.height);
      const scale = longest > maxDimension ? maxDimension / longest : 1;
      // 必须先把 PixelMap 完整解码出来再释放 ImageSource：
      // createPixelMap 返回的是延迟解码对象，提前 release 会得到空图
      const pixelMap = await source.createPixelMap({
        desiredSize: {
          width: Math.max(1, Math.round(info.size.width * scale)),
          height: Math.max(1, Math.round(info.size.height * scale)),
        },
        desiredPixelFormat: image.PixelMapFormat.RGBA_8888,
      });
      return pixelMap;
    } catch (err) {
      return null;
    } finally {
      if (source !== null) {
        try {
          await source.release();
        } catch (releaseErr) {
          // 释放失败不影响已解码的 PixelMap
        }
      }
    }
  }

  /** EXIF Orientation → 需要顺时针旋转的角度。 */
  private static exifRotation(orientation: string): number {
    switch (orientation.trim()) {
      case 'Top-right':
      case 'Left-bottom':
        return 180;
      case 'Bottom-right':
      case 'Right-top':
        return 90;
      case 'Left-top':
        return 270;
      default:
        return 0;
    }
  }

  /**
   * 在右下角烧录白色描边时间戳。
   * PixelMap 必须可编辑；这里直接复用解码出的 RGBA_8888 实例。
   */
  private static async drawWatermark(pixelMap: image.PixelMap, timestamp: number): Promise<void> {
    try {
      const info = await pixelMap.getImageInfo();
      const width = info.size.width;
      const height = info.size.height;
      const buffer = new ArrayBuffer(width * height * 4);
      await pixelMap.readPixelsToBuffer(buffer);
      const pixels = new Uint8ClampedArray(buffer);

      const text = formatDateTime(timestamp);
      const textHeight = Math.max(18, Math.round(width * 0.04));
      const margin = Math.round(width * 0.03);
      const glyphs = PhotoProcessor.renderTextPixels(text, textHeight);
      const x0 = Math.max(margin, width - margin - glyphs.width);
      const y0 = height - margin - glyphs.height;

      // 先画深色描边再画白字，保证在亮背景上也能看清
      PhotoProcessor.blit(pixels, width, height, glyphs, x0, y0, [0, 0, 0, 150], 2);
      PhotoProcessor.blit(pixels, width, height, glyphs, x0, y0, [255, 255, 255, 255], 0);

      await pixelMap.writeBufferToPixels(buffer);
    } catch (err) {
      // 水印失败不应影响上传：保留原图
    }
  }

  /**
   * 极简位图字体渲染：把时间戳画进一张 alpha 蒙版。
   *
   * ArkUI 的 Canvas 组件只能画在界面上，不能离屏合成到 PixelMap；
   * drawing 模块在部分设备上又依赖 GPU 上下文。这里用 5×7 点阵字体自己画，
   * 数字与常见符号足够，且完全在 CPU 侧完成，任何设备都能用。
   */
  private static renderTextPixels(text: string, height: number): GlyphMask {
    const scale = Math.max(1, Math.floor(height / 7));
    const charWidth = 5 * scale;
    const spacing = scale;
    const width = text.length * (charWidth + spacing) - spacing;
    const mask = new Uint8Array(width * (7 * scale));
    let cursor = 0;
    for (const ch of text) {
      const glyph = PhotoProcessor.glyph(ch);
      if (glyph !== null) {
        for (let row = 0; row < 7; row++) {
          const bits = glyph[row];
          for (let col = 0; col < 5; col++) {
            if ((bits & (1 << (4 - col))) !== 0) {
              for (let dy = 0; dy < scale; dy++) {
                for (let dx = 0; dx < scale; dx++) {
                  const x = cursor + col * scale + dx;
                  const y = row * scale + dy;
                  mask[y * width + x] = 255;
                }
              }
            }
          }
        }
      }
      cursor += charWidth + spacing;
    }
    return { width, height: 7 * scale, mask };
  }

  private static blit(
    pixels: Uint8ClampedArray,
    width: number,
    height: number,
    glyphs: GlyphMask,
    x0: number,
    y0: number,
    color: number[],
    grow: number,
  ): void {
    for (let y = 0; y < glyphs.height; y++) {
      for (let x = 0; x < glyphs.width; x++) {
        if (glyphs.mask[y * glyphs.width + x] === 0) {
          continue;
        }
        for (let gy = -grow; gy <= grow; gy++) {
          for (let gx = -grow; gx <= grow; gx++) {
            const px = x0 + x + gx;
            const py = y0 + y + gy;
            if (px < 0 || py < 0 || px >= width || py >= height) {
              continue;
            }
            const index = (py * width + px) * 4;
            const alpha = color[3] / 255;
            pixels[index] = Math.round(pixels[index] * (1 - alpha) + color[0] * alpha);
            pixels[index + 1] = Math.round(pixels[index + 1] * (1 - alpha) + color[1] * alpha);
            pixels[index + 2] = Math.round(pixels[index + 2] * (1 - alpha) + color[2] * alpha);
            pixels[index + 3] = 255;
          }
        }
      }
    }
  }

  /** 5×7 点阵字模；不支持的字符返回 null。 */
  private static glyph(ch: string): number[] | null {
    const table = GLYPHS[ch];
    return table === undefined ? null : table;
  }
}

interface GlyphMask {
  width: number;
  height: number;
  mask: Uint8Array;
}

/** 5×7 点阵：每行 5 位，共 7 行。只覆盖时间戳会用到的字符。 */
const GLYPHS: Record<string, number[]> = {
  '0': [0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110],
  '1': [0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110],
  '2': [0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111],
  '3': [0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110],
  '4': [0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010],
  '5': [0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110],
  '6': [0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110],
  '7': [0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000],
  '8': [0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110],
  '9': [0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100],
  '-': [0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000],
  ':': [0b00000, 0b00100, 0b00100, 0b00000, 0b00100, 0b00100, 0b00000],
  ' ': [0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000],
};

/** 把 JPEG 字节写到缓存文件，供系统分享面板使用。 */
export function writeTempJpeg(cacheDir: string, name: string, bytes: Uint8Array): string {
  const dir = FileUtil.join(cacheDir, 'share');
  FileUtil.ensureDir(dir);
  const path = FileUtil.join(dir, name);
  FileUtil.writeBytes(path, bytes);
  return path;
}

/** 读取文件全部字节；失败返回 null。 */
export function readFileBytes(path: string): Uint8Array | null {
  try {
    return FileUtil.readBytes(path);
  } catch (err) {
    return null;
  }
}

/** UTF-8 字符串转字节。 */
export function utf8(text: string): Uint8Array {
  return new util.TextEncoder().encodeInto(text);
}
