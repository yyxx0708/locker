import { formatMonthDay } from '../common/TimeFormat';
import { todayEpochDay } from '../common/Dates';

/**
 * 发送给掌管者时的数据范围选项。
 */
export enum SyncRange {
  TODAY = 'TODAY',
  THREE_DAYS = 'THREE_DAYS',
  WEEK = 'WEEK',
  MONTH = 'MONTH',
  ALL = 'ALL',
}

export function syncRangeLabel(range: SyncRange): string {
  switch (range) {
    case SyncRange.THREE_DAYS:
      return '三天内';
    case SyncRange.WEEK:
      return '一周内';
    case SyncRange.MONTH:
      return '一个月内';
    case SyncRange.ALL:
      return '全部导出';
    default:
      return '当天';
  }
}

export function syncRangeDays(range: SyncRange): number | null {
  switch (range) {
    case SyncRange.THREE_DAYS:
      return 3;
    case SyncRange.WEEK:
      return 7;
    case SyncRange.MONTH:
      return 30;
    case SyncRange.ALL:
      return null;
    default:
      return 1;
  }
}

export function syncRangeValues(): SyncRange[] {
  return [SyncRange.TODAY, SyncRange.THREE_DAYS, SyncRange.WEEK, SyncRange.MONTH, SyncRange.ALL];
}

export function parseSyncRange(value: string | undefined | null): SyncRange {
  const values = syncRangeValues();
  for (const range of values) {
    if (range === value) {
      return range;
    }
  }
  return SyncRange.TODAY;
}

/** 窗口（闭区间）。ALL 从 epoch 0 开始。 */
export interface DayWindow {
  from: number;
  to: number;
}

export function windowFor(range: SyncRange, todayEpochDayValue: number): DayWindow {
  const days = syncRangeDays(range);
  const to = todayEpochDayValue;
  const from = days === null ? 0 : Math.max(0, todayEpochDayValue - days + 1);
  const window: DayWindow = { from, to };
  return window;
}

/**
 * 从上次发送的日期开始到今天；从未发送过时等同于「当天」。
 * 用于日常增量发送，避免每次都重发整段历史。
 */
export function incrementalWindow(
  range: SyncRange,
  todayEpochDayValue: number,
  lastExportedAt: number | null,
): DayWindow {
  if (lastExportedAt === null) {
    return windowFor(range, todayEpochDayValue);
  }
  const lastDay = epochDayOfMillis(lastExportedAt);
  const window: DayWindow = {
    from: Math.min(lastDay, todayEpochDayValue),
    to: todayEpochDayValue,
  };
  return window;
}

function epochDayOfMillis(millis: number): number {
  const date = new Date(millis);
  return Math.floor(
    Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / 86_400_000,
  );
}

/** 增量窗口的起点是否就是今天（用于 UI 提示）。 */
export function isSingleDay(window: DayWindow, todayEpochDayValue: number): boolean {
  return window.from === window.to && window.to === todayEpochDayValue;
}

/** 把 epochDay 格式化为「M月d日」。 */
export function formatEpochDayLabel(epochDay: number): string {
  const utc = new Date(epochDay * 86_400_000);
  const date = new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
  return formatMonthDay(date.getTime());
}

/** 当前窗口的展示文案。 */
export function windowLabel(window: DayWindow, today: number): string {
  if (window.from <= 0) {
    return '全部历史';
  }
  if (isSingleDay(window, today)) {
    return formatEpochDayLabel(window.to);
  }
  return `${formatEpochDayLabel(window.from)} – ${formatEpochDayLabel(window.to)}`;
}

export function currentWindowLabel(window: DayWindow): string {
  return windowLabel(window, todayEpochDay());
}
