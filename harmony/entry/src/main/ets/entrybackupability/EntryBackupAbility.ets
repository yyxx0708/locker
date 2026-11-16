import { hilog } from '@kit.PerformanceAnalysisKit';
import { BackupExtensionAbility, BundleVersion } from '@kit.CoreFileKit';

const DOMAIN = 0x0000;

/** 系统备份：与 Android 版一致，关闭自动备份，数据只靠手动导出。 */
export default class EntryBackupAbility extends BackupExtensionAbility {
  async onBackup() {
    hilog.info(DOMAIN, 'Locker', 'onBackup');
    await Promise.resolve();
  }

  async onRestore(bundleVersion: BundleVersion) {
    hilog.info(DOMAIN, 'Locker', 'onRestore %{public}s', JSON.stringify(bundleVersion));
    await Promise.resolve();
  }
}
