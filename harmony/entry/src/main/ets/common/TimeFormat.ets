import { pad2, YearMonth } from './Dates';

/**
 * 时间与时长的展示格式。与 Android 版逐字对齐，避免两端数据对照时出现格式差异。
 */

/** yyyy-MM-dd HH:mm */
export function formatDateTime(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ` +
    `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/** yyyy年M月d日 */
export function formatDate(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
}

/** yyyy-MM-dd */
export function formatDateCompact(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** HH:mm */
export function formatTime(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/** yyyy年M月 */
export function formatMonth(anchor: YearMonth): string {
  return `${anchor.year}年${anchor.month}月`;
}

/** M月d日 */
export function formatMonthDay(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

/** M月d日 HH:mm */
export function formatMonthDayTime(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getMonth() + 1}月${d.getDate()}日 ${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
}

/** 「X 天 Y 小时 Z 分」，精确到分钟，秒数舍去。 */
export function formatDuration(millis: number): string {
  const totalMinutes = Math.floor(Math.max(0, millis) / 60_000);
  const days = Math.floor(totalMinutes / (24 * 60));
  const hours = Math.floor((totalMinutes % (24 * 60)) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) {
    return `${days} 天 ${hours} 小时 ${minutes} 分`;
  }
  if (hours > 0) {
    return `${hours} 小时 ${minutes} 分`;
  }
  return `${minutes} 分`;
}

/** 「HH:mm:ss」或「N天 HH:mm:ss」，用于佩戴中的实时走字。 */
export function formatDurationWithSeconds(millis: number): string {
  const totalSeconds = Math.floor(Math.max(0, millis) / 1000);
  const days = Math.floor(totalSeconds / 86_400);
  const hours = Math.floor((totalSeconds % 86_400) / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  const clock = `${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}`;
  return days > 0 ? `${days}天 ${clock}` : clock;
}

/** 图表与紧凑场景：「X天Y时」「X时Y分」「Z分」。 */
export function formatDurationShort(millis: number): string {
  const totalMinutes = Math.floor(Math.max(0, millis) / 60_000);
  const days = Math.floor(totalMinutes / (24 * 60));
  const hours = Math.floor((totalMinutes % (24 * 60)) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) {
    return `${days}天${hours}时`;
  }
  if (hours > 0) {
    return `${hours}时${minutes}分`;
  }
  return `${minutes}分`;
}

/** 月历格子里的极短时长：「X天」「X时」「Z分」，不足 1 分钟为空串。 */
export function compactWear(millis: number): string {
  const totalMinutes = Math.floor(Math.max(0, millis) / 60_000);
  const days = Math.floor(totalMinutes / (24 * 60));
  const hours = Math.floor((totalMinutes % (24 * 60)) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) {
    return `${days}天`;
  }
  if (hours > 0) {
    return `${hours}时`;
  }
  if (minutes > 0) {
    return `${minutes}分`;
  }
  return '';
}

/** 文件体积：B / KB / MB。 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 当前时间 yyyy年M月d日 HH:mm:ss，打卡页的实时时钟。 */
export function formatClock(epochMillis: number): string {
  const d = new Date(epochMillis);
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ` +
    `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}
