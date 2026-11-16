import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';

/**
 * 同步身份码的生成、哈希与加密。
 *
 * 参数必须与 Android 版逐字节一致，否则两端的同步包互相解不开：
 * - 身份码：8 位，取自去掉易混淆字符 0/O、1/I 的 32 字母表，展示为 XXXX-XXXX；
 * - 码哈希：SHA-256(salt ‖ 规范化码)，十六进制小写；
 * - 加密钥匙：PBKDF2-HMAC-SHA256(规范化码, salt, 120000 次, 32 字节)；
 * - 载荷：12 字节 IV 前缀 + AES-256-GCM 密文（含 16 字节认证标签）；
 * - 日报签名：以派生密钥做 HMAC-SHA256，取前 16 字节十六进制。
 *
 * 注意 salt 本身就是十六进制字符串，两端都把它当作 UTF-8 文本参与运算，
 * 而不是解码成字节，这一点很容易写错。
 */
export class SyncCrypto {
  private static readonly ALPHABET = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
  private static readonly CODE_LENGTH = 8;
  private static readonly KDF_ITERATIONS = 120_000;
  private static readonly GCM_TAG_BYTES = 16;
  static readonly IV_BYTES = 12;

  /** 生成形如 K7QM-3XPD 的身份码。 */
  static generateCode(): string {
    const random = cryptoFramework.createRandom();
    const blob = random.generateRandomSync(SyncCrypto.CODE_LENGTH);
    const bytes = blob.data;
    let chars = '';
    for (let i = 0; i < SyncCrypto.CODE_LENGTH; i++) {
      chars += SyncCrypto.ALPHABET.charAt(bytes[i] % SyncCrypto.ALPHABET.length);
    }
    return `${chars.substring(0, 4)}-${chars.substring(4)}`;
  }

  /** 输入归一化：忽略大小写、连字符与空白。 */
  static normalizeCode(input: string): string {
    let result = '';
    const upper = input.toUpperCase();
    for (let i = 0; i < upper.length; i++) {
      const ch = upper.charAt(i);
      if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z')) {
        result += ch;
      }
    }
    return result;
  }

  /** 16 字节随机盐的十六进制表示。 */
  static newSalt(): string {
    const random = cryptoFramework.createRandom();
    const blob = random.generateRandomSync(16);
    return SyncCrypto.toHex(blob.data);
  }

  /** 用于 zip 清单里的身份校验，不写明文码。 */
  static hashCode(salt: string, code: string): string {
    const md = cryptoFramework.createMd('SHA256');
    const saltBytes = new util.TextEncoder().encodeInto(salt);
    const codeBytes = new util.TextEncoder().encodeInto(SyncCrypto.normalizeCode(code));
    const combined = new Uint8Array(saltBytes.length + codeBytes.length);
    combined.set(saltBytes, 0);
    combined.set(codeBytes, saltBytes.length);
    md.updateSync({ data: combined });
    return SyncCrypto.toHex(md.digestSync().data);
  }

  static matches(salt: string, codeHash: string, input: string): boolean {
    return SyncCrypto.hashCode(salt, input) === codeHash;
  }

  /** 由身份码派生 AES-256 密钥（PBKDF2-HMAC-SHA256）。 */
  static deriveKeyBytes(salt: string, code: string): Uint8Array {
    const kdf = cryptoFramework.createKdf('PBKDF2|SHA256');
    const spec: cryptoFramework.PBKDF2Spec = {
      algName: 'PBKDF2',
      password: SyncCrypto.normalizeCode(code),
      salt: new util.TextEncoder().encodeInto(salt),
      iterations: SyncCrypto.KDF_ITERATIONS,
      keySize: 32,
    };
    return kdf.generateSecretSync(spec).data;
  }

  /** 日报二维码的 HMAC-SHA256 签名（取前 16 字节）。 */
  static hmacSign(salt: string, code: string, data: string): string {
    const keyBytes = SyncCrypto.deriveKeyBytes(salt, code);
    const mac = cryptoFramework.createMac('SHA256');
    const symKey = SyncCrypto.toSymKey(keyBytes);
    mac.initSync(symKey);
    mac.updateSync({ data: new util.TextEncoder().encodeInto(data) });
    const digest = mac.doFinalSync().data;
    return SyncCrypto.toHex(digest.slice(0, 16));
  }

  /** 返回 IV 前缀 + AES-GCM 密文（含认证标签）。 */
  static encrypt(plain: Uint8Array, keyBytes: Uint8Array): Uint8Array {
    const iv = cryptoFramework.createRandom().generateRandomSync(SyncCrypto.IV_BYTES).data;
    // GCM 本身是流式模式，不需要填充；Android 的 "AES/GCM/NoPadding" 与此等价。
    const cipher = cryptoFramework.createCipher('AES256|GCM|NoPadding');
    const params: cryptoFramework.GcmParamsSpec = {
      algName: 'GcmParamsSpec',
      iv: { data: iv },
      aad: { data: new Uint8Array(0) },
      authTag: { data: new Uint8Array(SyncCrypto.GCM_TAG_BYTES) },
    };
    cipher.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, SyncCrypto.toSymKey(keyBytes), params);
    const output = cipher.doFinalSync({ data: plain }).data;
    const result = new Uint8Array(iv.length + output.length);
    result.set(iv, 0);
    result.set(output, iv.length);
    return result;
  }

  static decrypt(blob: Uint8Array, keyBytes: Uint8Array): Uint8Array {
    if (blob.length <= SyncCrypto.IV_BYTES) {
      throw new Error('加密数据不完整');
    }
    const iv = blob.slice(0, SyncCrypto.IV_BYTES);
    const body = blob.slice(SyncCrypto.IV_BYTES);
    const tag = body.slice(body.length - SyncCrypto.GCM_TAG_BYTES);
    const cipherText = body.slice(0, body.length - SyncCrypto.GCM_TAG_BYTES);
    const cipher = cryptoFramework.createCipher('AES256|GCM|NoPadding');
    const params: cryptoFramework.GcmParamsSpec = {
      algName: 'GcmParamsSpec',
      iv: { data: iv },
      aad: { data: new Uint8Array(0) },
      authTag: { data: tag },
    };
    cipher.initSync(cryptoFramework.CryptoMode.DECRYPT_MODE, SyncCrypto.toSymKey(keyBytes), params);
    return cipher.doFinalSync({ data: cipherText }).data;
  }

  private static toSymKey(keyBytes: Uint8Array): cryptoFramework.SymKey {
    const generator = cryptoFramework.createSymKeyGenerator('AES256');
    return generator.convertKeySync({ data: keyBytes });
  }

  static toHex(bytes: Uint8Array): string {
    let out = '';
    for (let i = 0; i < bytes.length; i++) {
      out += bytes[i].toString(16).padStart(2, '0');
    }
    return out;
  }

  static fromHex(hex: string): Uint8Array {
    const length = Math.floor(hex.length / 2);
    const out = new Uint8Array(length);
    for (let i = 0; i < length; i++) {
      out[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  /** SHA-256 摘要，用于照片指纹与导出签名。 */
  static sha256(bytes: Uint8Array): Uint8Array {
    const md = cryptoFramework.createMd('SHA256');
    md.updateSync({ data: bytes });
    return md.digestSync().data;
  }
}
