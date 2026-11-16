import { cryptoFramework } from '@kit.CryptoArchitectureKit';

/**
 * 随机数工具。
 *
 * 每日随机数与身份码都用系统加密安全随机源（cryptoFramework.createRandom），
 * 不用 Math.random：后者在同一毫秒内的种子可能重复，号码会撞车。
 */
export class SecureRandom {
  private static readonly rng: cryptoFramework.Random = cryptoFramework.createRandom();

  /** [0, 1) 的随机小数。 */
  static nextFloat(): number {
    const bytes = SecureRandom.rng.generateRandomSync(4).data;
    const value = ((bytes[0] << 24) | (bytes[1] << 16) | (bytes[2] << 8) | bytes[3]) >>> 0;
    return value / 0x1_0000_0000;
  }

  /** [min, max] 闭区间内的整数。 */
  static nextInt(min: number, max: number): number {
    const span = max - min + 1;
    return min + Math.floor(SecureRandom.nextFloat() * span);
  }

  static bytes(length: number): Uint8Array {
    return SecureRandom.rng.generateRandomSync(length).data;
  }
}

/**
 * 生成 5 位数字（10000–99999）。
 */
export function generateFiveDigitNumber(): string {
  return `${SecureRandom.nextInt(10_000, 99_999)}`;
}

/** UUID v4 形式的字符串，仅用于本地标识。 */
export function randomUuid(): string {
  const bytes = SecureRandom.bytes(16);
  bytes[6] = (bytes[6] & 0x0F) | 0x40;
  bytes[8] = (bytes[8] & 0x3F) | 0x80;
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, '0');
  }
  return `${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-` +
    `${hex.substring(16, 20)}-${hex.substring(20, 32)}`;
}
