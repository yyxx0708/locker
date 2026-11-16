import { image } from '@kit.ImageKit';
import { drawing } from '@kit.ArkGraphics2D';
import { common2D } from '@kit.ArkGraphics2D';
import { FileUtil } from './FileUtil';
import { PhotoProcessor } from './PhotoProcessor';
import { generateQrPixelMap } from './QrCode';
import { formatDuration } from './TimeFormat';

/**
 * 日报分享卡。
 *
 * 把当天信息合成一张 1080×1920 的竖图，可直接发到聊天工具。
 * 版式与 Android 版对齐：深色渐变底、姓名与日期、二维码面板、
 * 当日随机数、三项关键数字（佩戴时长 / 事件次数 / 照片核验）与当天照片。
 *
 * 鸿蒙的 drawing.Canvas 直接绑定一张 PixelMap，没有 Android 的
 * Canvas/Surface 分离：先建一张 1080×1920 的 RGBA 位图，画完后编码成 PNG 落盘。
 */

export interface ReportCardInput {
  identityName: string;
  epochDay: number;
  number: string;
  wearMinutes: number;
  eventCount: number;
  photoLabel: string;
  photoPassed: boolean;
  photoBytes: Uint8Array | null;
  qrPayload: string;
}

const WIDTH = 1080;
const HEIGHT = 1920;
const MARGIN = 72;

export class ReportCard {
  /** 渲染并落盘，返回图片路径；失败返回 null。 */
  static async render(input: ReportCardInput, cacheDir: string): Promise<string | null> {
    let pixelMap: image.PixelMap | null = null;
    try {
      pixelMap = await image.createPixelMap(new ArrayBuffer(WIDTH * HEIGHT * 4), {
        size: { width: WIDTH, height: HEIGHT },
        srcPixelFormat: image.PixelMapFormat.RGBA_8888,
        pixelFormat: image.PixelMapFormat.RGBA_8888,
        editable: true,
        alphaType: image.AlphaType.PREMUL,
      });
      const canvas = new drawing.Canvas(pixelMap);

      ReportCard.drawBackground(canvas);

      ReportCard.drawText(canvas, 'LOCKER', MARGIN, 124, 34, '#C0FFFFFF', 0.16);
      ReportCard.drawText(canvas, input.identityName, MARGIN, 218, 76, '#FFFFFF', 0);
      const date = ReportCard.dateParts(input.epochDay);
      ReportCard.drawText(
        canvas,
        `${date.year}年${date.month}月${date.day}日 · ${date.weekday}`,
        MARGIN,
        284,
        36,
        '#C0FFFFFF',
        0,
      );

      // 二维码面板
      const qrSize = 560;
      const panelTop = 340;
      const panelHeight = qrSize + 100;
      const white = new drawing.Brush();
      white.setColor({ alpha: 255, red: 255, green: 255, blue: 255 });
      canvas.drawRoundRect(ReportCard.roundRect(MARGIN, panelTop, WIDTH - MARGIN,
        panelTop + panelHeight, 40));

      const qr = await generateQrPixelMap(input.qrPayload, qrSize);
      if (qr !== null) {
        const qrLeft = (WIDTH - qrSize) / 2;
        canvas.drawImage(qr, qrLeft, panelTop + 50);
        await qr.release();
      }
      ReportCard.drawText(
        canvas,
        '在 Locker 中「核验二维码」即可确认真伪',
        WIDTH / 2,
        panelTop + panelHeight - 28,
        28,
        '#6E6C66',
        0,
        true,
      );

      // 当日随机数
      const numberTop = panelTop + panelHeight + 84;
      ReportCard.drawText(canvas, '当日随机数', MARGIN, numberTop, 30, '#8CFFFFFF', 0.12);
      ReportCard.drawText(canvas, input.number, MARGIN - 2, numberTop + 130, 132, '#FFFFFF', 0.06);

      // 分隔线
      const dividerY = numberTop + 186;
      const linePen = new drawing.Pen();
      linePen.setStrokeWidth(2);
      linePen.setColor({ alpha: 51, red: 255, green: 255, blue: 255 });
      canvas.drawLine(MARGIN, dividerY, WIDTH - MARGIN, dividerY);

      // 左下：当天照片
      const contentTop = dividerY + 56;
      const photoSize = 380;
      await ReportCard.drawPhoto(canvas, input.photoBytes, MARGIN, contentTop, photoSize);

      // 右下：三项关键数字
      const statsLeft = MARGIN + photoSize + 56;
      ReportCard.drawStat(canvas, statsLeft, contentTop + 40, '佩戴时长',
        formatDuration(input.wearMinutes * 60_000), '#FFFFFF');
      ReportCard.drawStat(canvas, statsLeft, contentTop + 170, '事件次数',
        `${input.eventCount} 次`, '#FFFFFF');
      ReportCard.drawStat(canvas, statsLeft, contentTop + 300, '照片核验',
        input.photoLabel, input.photoPassed ? '#7FC4FF' : '#FF9A76');

      // 页脚
      ReportCard.drawText(
        canvas,
        '数据仅保存于本机 · 无账号 · 无云端同步',
        WIDTH / 2,
        HEIGHT - 64,
        26,
        '#8CFFFFFF',
        0,
        true,
      );

      const packer = image.createImagePacker();
      try {
        const buffer = await packer.packToData(pixelMap, { format: 'image/png', quality: 100 });
        const dir = FileUtil.join(cacheDir, 'share');
        FileUtil.ensureDir(dir);
        const path = FileUtil.join(dir, `locker-report-${Date.now()}.png`);
        FileUtil.writeBytes(path, new Uint8Array(buffer));
        return path;
      } finally {
        await packer.release();
      }
    } catch (err) {
      return null;
    } finally {
      if (pixelMap !== null) {
        await pixelMap.release();
      }
    }
  }

  private static drawBackground(canvas: drawing.Canvas): void {
    const brush = new drawing.Brush();
    brush.setShaderEffect(drawing.ShaderEffect.createLinearGradient(
      { x: 0, y: 0 },
      { x: WIDTH, y: HEIGHT },
      [
        ReportCard.argb(0xFF0B1220),
        ReportCard.argb(0xFF12304F),
        ReportCard.argb(0xFF1A1A19),
      ],
      drawing.TileMode.CLAMP,
      [0, 0.55, 1],
    ));
    canvas.drawRect(0, 0, WIDTH, HEIGHT);
    brush.setShaderEffect(null);

    // 品牌色光晕
    brush.setColor(ReportCard.color(0x4D2A78D6));
    canvas.drawCircle(WIDTH - 110, 90, 360);
    brush.setColor(ReportCard.color(0x2EEB6834));
    canvas.drawCircle(60, HEIGHT - 240, 320);
  }

  private static drawStat(
    canvas: drawing.Canvas,
    left: number,
    baseline: number,
    label: string,
    value: string,
    valueColor: string,
  ): void {
    ReportCard.drawText(canvas, label, left, baseline, 28, '#8CFFFFFF', 0);
    ReportCard.drawText(canvas, value, left, baseline + 62, 46, valueColor, 0);
  }

  private static async drawPhoto(
    canvas: drawing.Canvas,
    photoBytes: Uint8Array | null,
    left: number,
    top: number,
    size: number,
  ): Promise<void> {
    const brush = new drawing.Brush();
    if (photoBytes === null) {
      brush.setColor({ alpha: 20, red: 255, green: 255, blue: 255 });
      canvas.drawRoundRect(ReportCard.roundRect(left, top, left + size, top + size, 32));
      ReportCard.drawText(canvas, '今天未上传照片', left + size / 2, top + size / 2,
        28, '#8CFFFFFF', 0, true);
      return;
    }
    const pixelMap = await PhotoProcessor.decodeScaled(photoBytes, size);
    if (pixelMap === null) {
      return;
    }
    try {
      // 居中裁成正方形，避免变形
      const info = await pixelMap.getImageInfo();
      const side = Math.min(info.size.width, info.size.height);
      const srcLeft = (info.size.width - side) / 2;
      const srcTop = (info.size.height - side) / 2;
      canvas.save();
      canvas.clipRect({ left, top, right: left + size, bottom: top + size });
      canvas.drawImageRect(pixelMap, {
        left: srcLeft,
        top: srcTop,
        right: srcLeft + side,
        bottom: srcTop + side,
      }, {
        left,
        top,
        right: left + size,
        bottom: top + size,
      });
      canvas.restore();
    } finally {
      await pixelMap.release();
    }
  }

  private static drawText(
    canvas: drawing.Canvas,
    text: string,
    x: number,
    y: number,
    size: number,
    color: string,
    letterSpacing: number,
    centered: boolean = false,
  ): void {
    const font = new drawing.Font();
    font.setSize(size);
    const blob = drawing.TextBlob.makeFromString(
      text, font, drawing.TextEncoding.TEXT_ENCODING_UTF8);
    const pen = new drawing.Pen();
    pen.setColor(ReportCard.color(ReportCard.parseArgb(color)));
    pen.setAntiAlias(true);
    const drawX = centered
      ? x - font.measureText(text, drawing.TextEncoding.TEXT_ENCODING_UTF8) / 2
      : x;
    canvas.drawTextBlob(blob, drawX, y);
  }

  private static roundRect(
    left: number, top: number, right: number, bottom: number, radius: number,
  ): drawing.RoundRect {
    return new drawing.RoundRect({ left, top, right, bottom }, radius, radius);
  }

  private static parseArgb(value: string): number {
    let body = value.startsWith('#') ? value.substring(1) : value;
    if (body.length === 6) {
      body = `FF${body}`;
    }
    return parseInt(body, 16) >>> 0;
  }

  private static argb(value: number): number {
    return value >>> 0;
  }

  private static color(argb: number): common2D.Color {
    return {
      alpha: (argb >>> 24) & 0xFF,
      red: (argb >>> 16) & 0xFF,
      green: (argb >>> 8) & 0xFF,
      blue: argb & 0xFF,
    };
  }

  private static dateParts(epochDay: number): DateParts {
    const utc = new Date(epochDay * 86_400_000);
    const date = new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
    const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
    return {
      year: date.getFullYear(),
      month: date.getMonth() + 1,
      day: date.getDate(),
      weekday: weekdays[date.getDay()],
    };
  }
}

interface DateParts {
  year: number;
  month: number;
  day: number;
  weekday: string;
}
