import { SyncCrypto } from './SyncCrypto';

/**
 * 每日核验二维码的内容与签名。
 *
 * 格式（与 Android 版完全一致，二维码可互相核验）：
 * LK2|salt|identityId|epochDay|number|wearMinutes|eventCount|photoCheck|photoFingerprint|issuedAt|signature
 *
 * 签名 = HMAC-SHA256(由身份码派生的密钥, 除签名外的全部字段)，只有知道身份码的一方能验证。
 * photoFingerprint 为当日照片 JPEG 的 SHA-256 前 16 个十六进制字符，用于把日报与照片绑定。
 */
export enum PhotoCheck {
  NONE = 'N',
  PASS = 'P',
  MISMATCH = 'M',
  UNKNOWN = 'U',
}

export function photoCheckLabel(check: PhotoCheck): string {
  switch (check) {
    case PhotoCheck.PASS:
      return '✓ 照片号码核验通过';
    case PhotoCheck.MISMATCH:
      return '✗ 照片号码核验未通过';
    case PhotoCheck.UNKNOWN:
      return '未能识别照片中的号码';
    default:
      return '当日未上传照片';
  }
}

export function photoCheckFromCode(code: string): PhotoCheck | null {
  switch (code) {
    case 'N':
      return PhotoCheck.NONE;
    case 'P':
      return PhotoCheck.PASS;
    case 'M':
      return PhotoCheck.MISMATCH;
    case 'U':
      return PhotoCheck.UNKNOWN;
    default:
      return null;
  }
}

export interface DailyReportData {
  identityId: string;
  epochDay: number;
  number: string;
  wearMinutes: number;
  eventCount: number;
  photoCheck: PhotoCheck;
  photoFingerprint: string;
  issuedAt: number;
}

export interface ParsedDailyReport {
  salt: string;
  report: DailyReportData;
  signature: string;
}

const PREFIX = 'LK2';

export class DailyReport {
  static encode(report: DailyReportData, salt: string, code: string): string {
    const data = DailyReport.canonical(salt, report);
    return `${data}|${SyncCrypto.hmacSign(salt, code, data)}`;
  }

  static parse(payload: string): ParsedDailyReport {
    const parts = payload.trim().split('|');
    if (parts.length !== 11 || parts[0] !== PREFIX) {
      throw new Error('不是 Locker 日报二维码');
    }
    const bad = (): never => {
      throw new Error('日报内容不完整');
    };
    const epochDay = Number(parts[3]);
    const wearMinutes = Number(parts[5]);
    const eventCount = Number(parts[6]);
    const photoCheck = photoCheckFromCode(parts[7]);
    const issuedAt = Number(parts[9]);
    if (!Number.isFinite(epochDay) || !Number.isFinite(wearMinutes) ||
      !Number.isFinite(eventCount) || photoCheck === null || !Number.isFinite(issuedAt)) {
      bad();
    }
    if (parts[2].length === 0) {
      bad();
    }
    return {
      salt: parts[1],
      report: {
        identityId: parts[2],
        epochDay,
        number: parts[4],
        wearMinutes,
        eventCount,
        photoCheck: photoCheck as PhotoCheck,
        photoFingerprint: parts[8],
        issuedAt,
      },
      signature: parts[10],
    };
  }

  static verify(parsed: ParsedDailyReport, code: string): boolean {
    return SyncCrypto.hmacSign(parsed.salt, code, DailyReport.canonical(parsed.salt, parsed.report)) ===
      parsed.signature;
  }

  /** 照片内容指纹（SHA-256 前 16 个十六进制字符）。 */
  static photoFingerprint(jpeg: Uint8Array): string {
    return SyncCrypto.toHex(SyncCrypto.sha256(jpeg)).substring(0, 16);
  }

  /** 从 OCR 文本中提取 5 位数字候选（长数字串按滑动窗口拆分）。 */
  static findFiveDigitCandidates(text: string): string[] {
    const result: string[] = [];
    const regex = /\d{5,}/g;
    let match: RegExpExecArray | null = regex.exec(text);
    while (match !== null) {
      const digits = match[0];
      for (let i = 0; i + 5 <= digits.length; i++) {
        const candidate = digits.substring(i, i + 5);
        if (result.indexOf(candidate) < 0) {
          result.push(candidate);
        }
      }
      match = regex.exec(text);
    }
    return result;
  }

  private static canonical(salt: string, report: DailyReportData): string {
    return [
      PREFIX,
      salt,
      report.identityId,
      `${report.epochDay}`,
      report.number,
      `${report.wearMinutes}`,
      `${report.eventCount}`,
      report.photoCheck,
      report.photoFingerprint,
      `${report.issuedAt}`,
    ].join('|');
  }
}
