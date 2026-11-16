import { Palette, ChartPalette } from '../../common/Palette';
import { EventTypeStat } from '../../domain/Stats';
import { compactWear, formatDateTime, formatDuration, formatTime } from '../../common/TimeFormat';
import { YearMonth } from '../../common/Dates';
import { colorString } from '../../common/EventColors';
import { image } from '@kit.ImageKit';
import { tapFeedback } from '../../common/Haptics';
import { PhotoProcessor } from '../../common/PhotoProcessor';

/**
 * 当月照片缩略图仓库。
 *
 * 为什么不用 Map 或 Uint8Array 直接传参：
 * - PixelMap 是 native 句柄，跨组件按值传会失效（画不出来）；
 * - Map 内部变化不会触发子组件刷新；
 * - Uint8Array 通过 @Prop 传递时行为不稳定。
 * 用一个 @Observed 类包住 Map，子组件通过 @ObjectLink 共享同一份引用，
 * 父组件换数据后重建整个对象即可触发刷新。
 */
@Observed
export class CalendarPhotoStore {
  private photos: Map<number, image.PixelMap> = new Map<number, image.PixelMap>();

  get(day: number): image.PixelMap | undefined {
    return this.photos.get(day);
  }

  set(day: number, pixelMap: image.PixelMap): void {
    this.photos.set(day, pixelMap);
  }

  clear(): void {
    this.photos.clear();
  }

  size(): number {
    return this.photos.size;
  }
}

/**
 * 月历与日详情。
 *
 * 格子信息密度与 Android 版一致：日期数字、当天佩戴时长、最多 3 个事件色点，
 * 上传过照片的日期用照片铺底并压一层暗色蒙版，保证白色文字可读。
 */

/**
 * 月历。
 *
 * 必须是 @Component 而不是 @Builder 函数：@Builder 按值接收参数，
 * 父组件的 photos / wearBuckets 变化时不会重建，照片与热度就永远显示不出来。
 */
@Component
export struct MonthCalendar {
  @Prop palette: Palette;
  @Prop chart: ChartPalette;
  @Prop anchor: YearMonth;
  @Prop dayWear: number[] = [];
  @Prop eventStats: EventTypeStat[] = [];
  /**
   * 当月照片的缩略图，按「日」索引。
   * 用 @ObjectLink 绑定一个 @Observed 包装类：直接传 Map 或 Uint8Array
   * 都不会触发子组件刷新，PixelMap 也会因为跨组件传值而失效。
   */
  @ObjectLink photoStore: CalendarPhotoStore;
  onDayClick: (day: number) => void = () => {
  };

  build() {
  Column({ space: 4 }) {
    Row({ space: 4 }) {
      ForEach(['一', '二', '三', '四', '五', '六', '日'], (label: string) => {
        Text(label)
          .layoutWeight(1)
          .fontSize(10)
          .fontColor(this.palette.onSurfaceVariant)
          .textAlign(TextAlign.Center)
      }, (label: string) => label)
    }
    .width('100%')

    ForEach(MonthCalendarRows.buildRows(this.anchor), (row: number[], rowIndex: number) => {
      Row({ space: 4 }) {
        ForEach(row, (day: number, cellIndex: number) => {
          if (day > 0) {
            DayCell({
              palette: this.palette,
              chart: this.chart,
              day,
              wearMillis: MonthCalendarRows.wearOf(this.dayWear, day),
              eventStats: this.eventStats,
              photoStore: this.photoStore,
              maxWear: MonthCalendarRows.maxOf(this.dayWear),
              isToday: MonthCalendarRows.isToday(this.anchor, day),
              onDayTap: this.onDayClick,
            })
              .layoutWeight(1)
          } else {
            Blank().layoutWeight(1)
          }
        }, (day: number, cellIndex: number) => `${rowIndex}-${cellIndex}-${day}`)
      }
      .width('100%')
    }, (row: number[], rowIndex: number) => `week-${rowIndex}`)
  }
  .width('100%')
  }
}

class MonthCalendarRows {
  /** 按周切分的日期矩阵，0 表示空格。 */
  static buildRows(anchor: YearMonth): number[][] {
    const days = anchor.lengthOfMonth();
    const firstWeekday = new Date(anchor.year, anchor.month - 1, 1).getDay();
    // 周一为一周起点：周日(0) 变成 6
    const offset = (firstWeekday + 6) % 7;
    const cells: number[] = [];
    for (let i = 0; i < offset; i++) {
      cells.push(0);
    }
    for (let day = 1; day <= days; day++) {
      cells.push(day);
    }
    while (cells.length % 7 !== 0) {
      cells.push(0);
    }
    const rows: number[][] = [];
    for (let i = 0; i < cells.length; i += 7) {
      rows.push(cells.slice(i, i + 7));
    }
    return rows;
  }

  static wearOf(dayWear: number[], day: number): number {
    return day >= 1 && day <= dayWear.length ? dayWear[day - 1] : 0;
  }

  static maxOf(dayWear: number[]): number {
    let max = 0;
    for (const value of dayWear) {
      max = Math.max(max, value);
    }
    return max;
  }

  static isToday(anchor: YearMonth, day: number): boolean {
    const now = new Date();
    return now.getFullYear() === anchor.year && now.getMonth() + 1 === anchor.month &&
      now.getDate() === day;
  }
}

@Component
struct DayCell {
  @Prop palette: Palette;
  @Prop chart: ChartPalette;
  @Prop day: number;
  @Prop wearMillis: number;
  @Prop eventStats: EventTypeStat[] = [];
  /**
   * 当天照片的 JPEG 字节；为 undefined 表示没有照片。由 DayCell 自行解码。
   *
   * 必须带 @Watch：照片是异步加载的，DayCell 首次创建时父组件的 Map 还是空的，
   * 之后数据到达只会更新 @Prop，不会重新执行 aboutToAppear。
   */
  /** 照片仓库，取本格子的缩略图。 */
  @ObjectLink photoStore: CalendarPhotoStore;
  @Prop maxWear: number;
  @Prop isToday: boolean;
  /** 按压反馈：按下缩到 0.95，松开或取消时回弹 */
  @State private cellPressed: boolean = false;
  /** 本格子的照片；从共享仓库里取，避免跨组件传 PixelMap。 */
  private photoOf(): image.PixelMap | undefined {
    return this.photoStore.get(this.day);
  }
  onDayTap: (day: number) => void = () => {
  };

  private cellBackground(): string {
    if (this.wearMillis <= 0) {
      return this.palette.surfaceContainerLow;
    }
    const fraction = this.maxWear > 0
      ? Math.min(1, this.wearMillis / this.maxWear)
      : 0;
    // 与 Android 版一致：0.08 起步，按占比最多再叠 0.30
    const alpha = 0.08 + 0.30 * fraction;
    return hexWithAlpha(this.chart.series1, alpha);
  }

  private dayEvents(): EventTypeStat[] {
    const result: EventTypeStat[] = [];
    for (const stat of this.eventStats) {
      const count = this.day - 1 < stat.buckets.length ? stat.buckets[this.day - 1] : 0;
      if (count > 0) {
        result.push(stat);
      }
    }
    return result;
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      if (this.photoOf() !== undefined) {
        // 照片容器：异步解码完成后淡入，避免瞬间弹出。
        // Image 必须给绝对尺寸：在固定高度的 Stack 里用百分比，
        // 部分机型上会算出 0 高导致画不出来。
        Stack({ alignContent: Alignment.TopStart }) {
          Image(this.photoOf())
            .width('100%')
            .height(60)
            .objectFit(ImageFit.Cover)
            .interpolation(ImageInterpolation.Medium)
            .syncLoad(true)
          Column()
            .width('100%')
            .height(60)
            .backgroundColor('#61000000')
        }
        .width('100%')
        .height(60)
        .transition(TransitionEffect.OPACITY.animation({ duration: 250 }))
      }


      Column() {
        Text(`${this.day}`)
          .fontSize(10)
          .fontWeight(this.isToday ? FontWeight.Bold : FontWeight.Normal)
          .fontColor(this.photoOf() !== undefined
            ? '#FFFFFF'
            : (this.isToday ? this.palette.primary : this.palette.onSurfaceVariant))
          .animation({ duration: 250, curve: Curve.EaseOut })

        Blank()

        Text(compactWear(this.wearMillis))
          .fontSize(8)
          .maxLines(1)
          .fontColor(this.photoOf() !== undefined ? '#FFFFFF' : this.palette.onSurface)
          .animation({ duration: 250, curve: Curve.EaseOut })

        Row({ space: 2 }) {
          ForEach(this.dayEvents().slice(0, 3), (stat: EventTypeStat) => {
            Row()
              .width(6)
              .height(6)
              .borderRadius(3)
              .backgroundColor(colorString(stat.type.colorArgb))
              .borderWidth(1)
              .borderColor(this.photoOf() !== undefined ? '#BFFFFFFF' : this.palette.surface)
          }, (stat: EventTypeStat) => stat.type.typeKey)
          if (this.dayEvents().length > 3) {
            Text(`+${this.dayEvents().length - 3}`)
              .fontSize(8)
              .fontColor(this.photoOf() !== undefined ? '#D9FFFFFF' : this.palette.onSurfaceVariant)
          }
        }
        .height(8)
      }
      .width('100%')
      .height('100%')
      .padding(4)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height(60)
    .borderRadius(6)
    .backgroundColor(this.cellBackground())
    .animation({ duration: 250, curve: Curve.EaseOut })
    .borderWidth(this.isToday ? 1.5 : 0)
    .borderColor(this.palette.primary)
    .clip(true)
    .scale({ x: this.cellPressed ? 0.95 : 1, y: this.cellPressed ? 0.95 : 1 })
    .onTouch((event: TouchEvent) => {
      if (event.type === TouchType.Down) {
        animateTo({ duration: 150, curve: Curve.EaseOut }, () => {
          this.cellPressed = true;
        });
      } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
        animateTo({ duration: 150, curve: Curve.EaseOut }, () => {
          this.cellPressed = false;
        });
      }
    })
    .onClick(() => {
      tapFeedback();
      this.onDayTap(this.day);
    })
  }
}

/** 日详情里的 24 小时时间轴：佩戴画成横条，事件画成圆点。 */
@Component
export struct DayTimeline {
  @Prop palette: Palette;
  @Prop dayStart: number;
  @Prop dayEnd: number;
  segments: TimelineSegment[] = [];
  events: TimelineEvent[] = [];

  build() {
    Column({ space: 3 }) {
      Stack({ alignContent: Alignment.Start }) {
        Row()
          .width('100%')
          .height(8)
          .borderRadius(4)
          .backgroundColor(this.palette.surfaceContainerHighest)

        ForEach(this.segments, (segment: TimelineSegment) => {
          Row()
            .width(`${this.percentWidth(segment.start, segment.end)}%`)
            .height(8)
            .borderRadius(4)
            .backgroundColor(this.palette.primary)
            .margin({ left: `${this.percentStart(segment.start)}%` })
        }, (segment: TimelineSegment, index: number) => `seg-${index}`)

        ForEach(this.events, (event: TimelineEvent) => {
          Row()
            .width(10)
            .height(10)
            .borderRadius(5)
            .backgroundColor(colorString(event.colorArgb))
            .borderWidth(1.5)
            .borderColor(this.palette.surface)
            .margin({ left: `${this.percentStart(event.at)}%` })
        }, (event: TimelineEvent, index: number) => `evt-${index}`)
      }
      .width('100%')
      .height(18)

      Row() {
        ForEach(['0', '6', '12', '18', '24'], (label: string) => {
          Text(label)
            .fontSize(9)
            .fontColor(this.palette.onSurfaceVariant)
        }, (label: string) => label)
      }
      .width('100%')
      .justifyContent(FlexAlign.SpaceBetween)
    }
    .width('100%')
  }

  private span(): number {
    return Math.max(1, this.dayEnd - this.dayStart);
  }

  private percentStart(at: number): number {
    const value = ((at - this.dayStart) / this.span()) * 100;
    return Math.max(0, Math.min(100, value));
  }

  private percentWidth(start: number, end: number): number {
    return Math.max(0, this.percentStart(end) - this.percentStart(start));
  }
}

export interface TimelineSegment {
  start: number;
  end: number;
}

export interface TimelineEvent {
  at: number;
  colorArgb: number;
}

/** #RRGGBB + alpha → #AARRGGBB。 */
export function hexWithAlpha(hex: string, alpha: number): string {
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255)
    .toString(16).padStart(2, '0').toUpperCase();
  const body = hex.startsWith('#') ? hex.substring(1) : hex;
  const rgb = body.length === 8 ? body.substring(2) : body;
  return `#${a}${rgb}`;
}
