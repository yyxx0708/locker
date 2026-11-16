import { vibrator } from '@kit.SensorServiceKit';

/**
 * 触感反馈。
 *
 * 长按事件类型、点确认按钮这类「有后果」的操作给一次轻振动，
 * 与 Android 版的 performHapticFeedback(LongPress) 对应。
 * 设备不支持振动（如平板、模拟器）时静默忽略，不影响主流程。
 */
export function tapFeedback(): void {
  try {
    vibrator.startVibration(
      { type: 'time', duration: 10 },
      { id: 0, usage: 'touch' },
    ).catch(() => {
      // 无振动马达或权限不足时忽略
    });
  } catch (err) {
    // 同步抛错同样忽略
  }
}
