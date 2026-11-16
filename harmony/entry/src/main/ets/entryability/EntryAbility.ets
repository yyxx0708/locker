import { AbilityConstant, ConfigurationConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { taskpool } from '@kit.ArkTS';
import { LockerStore } from '../data/LockerStore';
import { KeyValueStore } from '../data/KeyValueStore';
import { AppPreferences } from '../data/AppPreferences';
import { ThemePreferences } from '../common/Theme';
import { PendingOpenFile } from '../common/PendingOpenFile';
import { FileUtil } from '../common/FileUtil';
import { fileIo as fs } from '@kit.CoreFileKit';

const DOMAIN = 0x0000;
/** 系统把 URI 放在 want.uri 之外时，还会复制一份到这个参数键里。 */
const WANT_URI_KEY = 'ohos.want.uri';

/**
 * 在 taskpool 线程里删除一天前的临时文件。
 * 相机照片直接写在缓存根目录（camera_<时间戳>.jpg），
 * 二维码、分享与同步包各自有子目录，两类都要覆盖。
 */
@Concurrent
function cleanStaleCacheTask(cacheDir: string): void {
  // @Concurrent 只能读局部变量与 import，常量在这里就地展开
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  const dirs: string[] = [
    cacheDir,
    FileUtil.join(cacheDir, 'camera'),
    FileUtil.join(cacheDir, 'sync'),
    FileUtil.join(cacheDir, 'qr'),
    FileUtil.join(cacheDir, 'share'),
  ];
  for (const dir of dirs) {
    // 缓存根目录只动本应用命名的相机临时文件，避免误删系统或其他模块的缓存
    const cameraOnly = dir === cacheDir;
    const names: string[] = FileUtil.listFiles(dir);
    for (const name of names) {
      if (cameraOnly && !name.startsWith('camera_')) {
        continue;
      }
      const path = FileUtil.join(dir, name);
      try {
        const stat = fs.statSync(path);
        // 只处理文件：子目录交给各自的循环处理
        if (stat.isDirectory()) {
          continue;
        }
        // stat.mtime 单位是秒，换算成毫秒再比较
        if (stat.mtime * 1000 < cutoff) {
          FileUtil.remove(path);
        }
      } catch (err) {
        // 单个文件失败不影响其他清理
      }
    }
  }
}

/**
 * 应用入口。
 * 负责初始化数据库与偏好，并把深色模式同步到系统窗口，
 * 保证启动页与首帧不会先闪一下浅色再切到深色。
 */
export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, 'Locker', 'Ability onCreate');
    KeyValueStore.get().init(this.context);
    // 冷启动时由外部应用带进来的同步包
    EntryAbility.captureOpenFile(want);
    this.cleanStaleCache();
  }

  /**
   * 清理一天前的相机、同步、二维码与分享临时文件。
   * 进程被杀时可能留下这些文件，长期不清理会占用缓存空间。
   * 只删 24 小时前的，避免影响正在进行中的操作；
   * 放到 taskpool 里执行，不阻塞启动。
   */
  private cleanStaleCache(): void {
    const cacheDir = this.context.cacheDir;
    try {
      taskpool.execute(cleanStaleCacheTask, cacheDir).catch((err: Error) => {
        hilog.warn(DOMAIN, 'Locker', 'Stale cache cleanup failed: %{public}s', err.message);
      });
    } catch (err) {
      // taskpool 不可用时退回主线程：只遍历几个目录，耗时可忽略
      try {
        cleanStaleCacheTask(cacheDir);
      } catch (inner) {
        // 清理失败不影响启动
      }
    }
  }

  /**
   * 应用已在运行时又从外部打开文件（QQ 里长按 zip →「用其他应用打开」）。
   * 这里只登记，具体导入交给页面消费。
   */
  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, 'Locker', 'Ability onNewWant');
    EntryAbility.captureOpenFile(want);
  }

  /** 从 want 里取文件 URI：优先 want.uri，其次 parameters 里的 ohos.want.uri。 */
  private static captureOpenFile(want: Want): void {
    let uri = '';
    if (want.uri !== undefined && want.uri !== null && want.uri.length > 0) {
      uri = want.uri;
    } else {
      const parameters = want.parameters;
      if (parameters !== undefined && parameters !== null) {
        const value: Object | undefined = parameters[WANT_URI_KEY];
        if (typeof value === 'string') {
          uri = value as string;
        }
      }
    }
    if (uri.length > 0) {
      PendingOpenFile.put(uri);
      hilog.info(DOMAIN, 'Locker', 'Pending open file captured');
    }
    // 桌面长按快捷方式携带的动作（如 sendToday）
    const params = want.parameters;
    if (params !== undefined && params !== null) {
      const action: Object | undefined = params['lockerAction'];
      if (typeof action === 'string') {
        PendingOpenFile.putAction(action);
        hilog.info(DOMAIN, 'Locker', 'Pending action captured: %{public}s', action);
      }
    }
  }

  onDestroy(): void {
    LockerStore.close();
    hilog.info(DOMAIN, 'Locker', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    this.applyColorMode();
    // 数据库初始化完成后再加载页面，避免首帧读到未就绪的存储
    LockerStore.init(this.context)
      .then(() => {
        windowStage.loadContent('pages/Index', (err: BusinessError) => {
          if (err.code) {
            hilog.error(DOMAIN, 'Locker', 'Failed to load content: %{public}s',
              JSON.stringify(err));
          }
        });
      })
      .catch((err: BusinessError) => {
        hilog.error(DOMAIN, 'Locker', 'Database init failed: %{public}s', err.message);
        windowStage.loadContent('pages/Index', () => {
        });
      });
  }

  onWindowStageDestroy(): void {
    hilog.info(DOMAIN, 'Locker', 'Ability onWindowStageDestroy');
  }

  onForeground(): void {
    // 从后台回来时重新读取一次主题，用户可能在系统设置里切换过深浅色
    this.applyColorMode();
  }

  onBackground(): void {
    hilog.info(DOMAIN, 'Locker', 'Ability onBackground');
  }

  /** 跟随系统 / 白天 / 黑暗。 */
  private applyColorMode(): void {
    try {
      const mode = ThemePreferences.toColorMode(AppPreferences.themeMode());
      this.context.getApplicationContext().setColorMode(mode);
    } catch (err) {
      hilog.error(DOMAIN, 'Locker', 'Failed to set colorMode: %{public}s',
        JSON.stringify(err));
    }
  }
}
