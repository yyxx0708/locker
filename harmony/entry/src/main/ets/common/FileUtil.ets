import { fileIo as fs } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';

/**
 * 文件读写小工具。
 *
 * 同步包会经过「解压 → 读清单 → 解密 → 再解压」几轮磁盘往返，
 * 所有临时文件都放在应用 cache 目录下，并在用完后立即删除。
 */
export class FileUtil {
  static ensureDir(path: string): void {
    if (!fs.accessSync(path)) {
      fs.mkdirSync(path, true);
    }
  }

  static readBytes(path: string): Uint8Array {
    const stat = fs.statSync(path);
    const buffer = new ArrayBuffer(stat.size);
    const result = new Uint8Array(buffer);
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    try {
      let offset = 0;
      while (offset < result.length) {
        const chunk = new ArrayBuffer(Math.min(1024 * 1024, result.length - offset));
        const count = fs.readSync(file.fd, chunk);
        if (count <= 0) {
          throw new Error('文件读取不完整');
        }
        result.set(new Uint8Array(chunk, 0, count), offset);
        offset += count;
      }
    } finally {
      fs.closeSync(file);
    }
    return result;
  }

  static readText(path: string): string {
    return fs.readTextSync(path);
  }

  static writeBytes(path: string, bytes: Uint8Array): void {
    const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      let offset = 0;
      while (offset < bytes.length) {
        const end = Math.min(offset + 1024 * 1024, bytes.length);
        const chunk = bytes.buffer.slice(bytes.byteOffset + offset, bytes.byteOffset + end);
        const count = fs.writeSync(file.fd, chunk);
        if (count <= 0) {
          throw new Error('文件写入不完整');
        }
        offset += count;
      }
    } finally {
      fs.closeSync(file);
    }
  }

  static writeText(path: string, text: string): void {
    const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    try {
      fs.writeSync(file.fd, text);
    } finally {
      fs.closeSync(file);
    }
  }

  /** 递归删除，不存在时静默返回。 */
  static remove(path: string): void {
    try {
      if (!fs.accessSync(path)) {
        return;
      }
      const stat = fs.statSync(path);
      if (stat.isDirectory()) {
        for (const name of fs.listFileSync(path)) {
          FileUtil.remove(`${path}/${name}`);
        }
        fs.rmdirSync(path);
      } else {
        fs.unlinkSync(path);
      }
    } catch (err) {
      // 清理失败不影响主流程
    }
  }

  static exists(path: string): boolean {
    try {
      return fs.accessSync(path);
    } catch (err) {
      return false;
    }
  }

  static listFiles(path: string): string[] {
    try {
      return fs.listFileSync(path);
    } catch (err) {
      return [];
    }
  }

  static size(path: string): number {
    try {
      return fs.statSync(path).size;
    } catch (err) {
      return 0;
    }
  }

  /** UTF-8 编码。 */
  static encodeUtf8(text: string): Uint8Array {
    return new util.TextEncoder().encodeInto(text);
  }

  /** UTF-8 解码。 */
  static decodeUtf8(bytes: Uint8Array): string {
    return util.TextDecoder.create('utf-8').decodeToString(bytes);
  }

  /** 拼接路径，自动处理末尾斜杠。 */
  static join(...parts: string[]): string {
    let result = '';
    for (const part of parts) {
      if (part.length === 0) {
        continue;
      }
      if (result.length === 0) {
        result = part;
      } else {
        result = result.endsWith('/') ? result + part : `${result}/${part}`;
      }
    }
    return result;
  }
}
