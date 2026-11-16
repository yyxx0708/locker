/**
 * 日期工具。
 *
 * 全项目统一用两个时间刻度：
 * - epochMillis：Unix 毫秒时间戳，分段、事件、随机数都用它；
 * - epochDay：自 1970-01-01 起的天数，按**本地日历**计算，照片与每日随机数用它。
 *
 * epochDay 的换算必须走「本地年月日 → UTC 零点」，不能直接用毫秒除以一天，
 * 否则东八区会整体偏移 8 小时、跨日边界错位。
 */

export const MILLIS_PER_DAY = 86_400_000;

/** 本地日期的 epochDay。 */
export function epochDayOf(date: Date): number {
  return Math.floor(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()) / MILLIS_PER_DAY);
}

/** 今天（本地）的 epochDay。 */
export function todayEpochDay(): number {
  return epochDayOf(new Date());
}

/** epochDay 对应的本地日期（零点）。 */
export function dateOfEpochDay(epochDay: number): Date {
  const utc = new Date(epochDay * MILLIS_PER_DAY);
  return new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
}

/** 某天本地零点的毫秒时间戳。 */
export function startOfDayMillis(epochDay: number): number {
  return dateOfEpochDay(epochDay).getTime();
}

/** 某天本地 24:00（次日零点）的毫秒时间戳。 */
export function endOfDayMillis(epochDay: number): number {
  return startOfDayMillis(epochDay + 1);
}

/** 某个月份的天数。month 为 0-11。 */
export function daysInMonth(year: number, month: number): number {
  return new Date(year, month + 1, 0).getDate();
}

/** 某个月 1 号是周几（0=周日）。month 为 0-11。 */
export function firstWeekdayOfMonth(year: number, month: number): number {
  return new Date(year, month, 1).getDay();
}

/**
 * 年月。替代 Java 的 YearMonth：月份从 1 开始，与界面文案一致。
 * 内部比较与运算都走毫秒构造，避免手写进位逻辑出错。
 */
export class YearMonth {
  readonly year: number;
  /** 1-12 */
  readonly month: number;

  constructor(year: number, month: number) {
    let y = year;
    let m = month;
    while (m < 1) {
      m += 12;
      y -= 1;
    }
    while (m > 12) {
      m -= 12;
      y += 1;
    }
    this.year = y;
    this.month = m;
  }

  static now(): YearMonth {
    const now = new Date();
    return new YearMonth(now.getFullYear(), now.getMonth() + 1);
  }

  /** 由毫秒时间戳所在月份构造。 */
  static ofMillis(millis: number): YearMonth {
    const date = new Date(millis);
    return new YearMonth(date.getFullYear(), date.getMonth() + 1);
  }

  static ofEpochDay(epochDay: number): YearMonth {
    const date = dateOfEpochDay(epochDay);
    return new YearMonth(date.getFullYear(), date.getMonth() + 1);
  }

  /** 当月天数。 */
  lengthOfMonth(): number {
    return daysInMonth(this.year, this.month - 1);
  }

  /** 当月 1 号的 epochDay。 */
  firstEpochDay(): number {
    return epochDayOf(new Date(this.year, this.month - 1, 1));
  }

  /** 当月最后一天的 epochDay。 */
  lastEpochDay(): number {
    return epochDayOf(new Date(this.year, this.month - 1, this.lengthOfMonth()));
  }

  /** 第 day 天（1 起）的 epochDay。 */
  epochDayOf(day: number): number {
    return epochDayOf(new Date(this.year, this.month - 1, day));
  }

  /** 当月 1 号本地零点毫秒。 */
  startMillis(): number {
    return new Date(this.year, this.month - 1, 1).getTime();
  }

  /** 下月 1 号本地零点毫秒（即本月结束时刻）。 */
  endMillis(): number {
    return new Date(this.year, this.month, 1).getTime();
  }

  plusMonths(delta: number): YearMonth {
    return new YearMonth(this.year, this.month + delta);
  }

  plusYears(delta: number): YearMonth {
    return new YearMonth(this.year + delta, this.month);
  }

  equals(other: YearMonth): boolean {
    return this.year === other.year && this.month === other.month;
  }

  compareTo(other: YearMonth): number {
    if (this.year !== other.year) {
      return this.year - other.year;
    }
    return this.month - other.month;
  }

  /** 供 Map 键使用。 */
  key(): string {
    return `${this.year}-${this.month}`;
  }
}

/** 补零。 */
export function pad2(value: number): string {
  return value < 10 ? `0${value}` : `${value}`;
}
