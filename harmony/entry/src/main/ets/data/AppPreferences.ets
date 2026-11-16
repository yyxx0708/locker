import { ThemeMode, parseThemeMode } from '../common/ThemeMode';
import { KeyValueStore } from './KeyValueStore';

const KEY_THEME_MODE = 'theme_mode';
const KEY_SELECTED_PERSON_ID = 'selected_person_id';
const KEY_WATERMARK = 'photo_watermark';
const KEY_CHECK_NUMBER = 'photo_check_number';
const KEY_SYNC_RANGE = 'sync_range';
const KEY_FULLSCREEN_MIRRORED = 'fullscreen_number_mirrored';
const KEY_LAST_IMPORT_PREFIX = 'last_import_hash_';

/**
 * 本机偏好项。
 *
 * 键名与 Android 版保持一致（同一个 SharedPreferences 文件、同样的 key），
 * 方便两端对照排查；鸿蒙端是独立安装，不共享实际的偏好文件。
 */
export class AppPreferences {
  private static store(): KeyValueStore {
    return KeyValueStore.get();
  }

  // ---- 主题 ----

  static themeMode(): ThemeMode {
    return parseThemeMode(AppPreferences.store().getString(KEY_THEME_MODE, ThemeMode.SYSTEM));
  }

  static setThemeMode(mode: ThemeMode): void {
    AppPreferences.store().putString(KEY_THEME_MODE, mode);
  }

  // ---- 当前人物 ----

  /** 未选择时返回 null，由上层回落到第一个人物。 */
  static selectedPersonId(): number | null {
    const value = AppPreferences.store().getNumber(KEY_SELECTED_PERSON_ID, -1);
    return value > 0 ? value : null;
  }

  static setSelectedPersonId(personId: number | null): void {
    if (personId === null) {
      AppPreferences.store().delete(KEY_SELECTED_PERSON_ID);
    } else {
      AppPreferences.store().putNumber(KEY_SELECTED_PERSON_ID, personId);
    }
  }

  // ---- 照片开关 ----

  static watermarkEnabled(): boolean {
    return AppPreferences.store().getBoolean(KEY_WATERMARK, false);
  }

  static setWatermarkEnabled(enabled: boolean): void {
    AppPreferences.store().putBoolean(KEY_WATERMARK, enabled);
  }

  static checkNumberAfterUpload(): boolean {
    return AppPreferences.store().getBoolean(KEY_CHECK_NUMBER, false);
  }

  static setCheckNumberAfterUpload(enabled: boolean): void {
    AppPreferences.store().putBoolean(KEY_CHECK_NUMBER, enabled);
  }

  // ---- 同步 ----

  static syncRange(): string {
    return AppPreferences.store().getString(KEY_SYNC_RANGE, 'TODAY');
  }

  static setSyncRange(value: string): void {
    AppPreferences.store().putString(KEY_SYNC_RANGE, value);
  }

  /** 记录每个同步身份最近一次成功应用的包，用于识别重复导入。 */
  static lastImportHash(identityId: string): string | null {
    const value = AppPreferences.store().getString(`${KEY_LAST_IMPORT_PREFIX}${identityId}`, '');
    return value.length > 0 ? value : null;
  }

  static setLastImportHash(identityId: string, hash: string): void {
    AppPreferences.store().putString(`${KEY_LAST_IMPORT_PREFIX}${identityId}`, hash);
  }

  // ---- 全屏随机数 ----

  static fullScreenMirrored(): boolean {
    return AppPreferences.store().getBoolean(KEY_FULLSCREEN_MIRRORED, false);
  }

  static setFullScreenMirrored(mirrored: boolean): void {
    AppPreferences.store().putBoolean(KEY_FULLSCREEN_MIRRORED, mirrored);
  }
}
