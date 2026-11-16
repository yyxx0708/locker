import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';

const PREF_NAME = 'locker_settings';

/**
 * 轻量键值存储，对应 Android 的 SharedPreferences。
 *
 * 直接使用 preferences 的同步接口：偏好项都很小（主题、人物选择、开关），
 * 在界面回调里同步读写比每处都 await 更不容易写错，
 * 也不会出现「刚切换主题、页面读到的还是旧值」这类时序问题。
 */
export class KeyValueStore {
  private static instance: KeyValueStore | null = null;
  private store: preferences.Preferences | null = null;

  static get(): KeyValueStore {
    if (KeyValueStore.instance === null) {
      KeyValueStore.instance = new KeyValueStore();
    }
    return KeyValueStore.instance;
  }

  /** 由 EntryAbility 在加载界面之前调用一次。 */
  init(context: common.Context): void {
    if (this.store !== null) {
      return;
    }
    this.store = preferences.getPreferencesSync(context, { name: PREF_NAME });
  }

  private require(): preferences.Preferences {
    const store = this.store;
    if (store === null) {
      throw new Error('偏好存储尚未初始化');
    }
    return store;
  }

  getString(key: string, fallback: string): string {
    const value = this.require().getSync(key, fallback);
    return typeof value === 'string' ? value : fallback;
  }

  putString(key: string, value: string): void {
    const store = this.require();
    store.putSync(key, value);
    store.flushSync();
  }

  getNumber(key: string, fallback: number): number {
    const value = this.require().getSync(key, fallback);
    return typeof value === 'number' ? value : fallback;
  }

  putNumber(key: string, value: number): void {
    const store = this.require();
    store.putSync(key, value);
    store.flushSync();
  }

  getBoolean(key: string, fallback: boolean): boolean {
    const value = this.require().getSync(key, fallback);
    return typeof value === 'boolean' ? value : fallback;
  }

  putBoolean(key: string, value: boolean): void {
    const store = this.require();
    store.putSync(key, value);
    store.flushSync();
  }

  delete(key: string): void {
    const store = this.require();
    store.deleteSync(key);
    store.flushSync();
  }
}
