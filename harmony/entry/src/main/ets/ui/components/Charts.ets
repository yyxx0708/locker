import { Palette, ChartPalette } from '../../common/Palette';
import { EventTypeStat, StatsMode } from '../../domain/Stats';
import { formatDuration, formatDurationShort } from '../../common/TimeFormat';
import { colorString } from '../../common/EventColors';

/**
 * 统计图表。
 *
 * 与 Android 版一致：佩戴时长是单色柱状图，事件次数是按类型堆叠的柱状图；
 * 点按柱子查看该桶的数值，再点一次取消。柱子高度用比例计算，
 * 不引入图表库，避免额外依赖与主题不一致。
 */

@Builder
export function WearBarChart(
  palette: Palette,
  chart: ChartPalette,
  values: number[],
  labels: string[],
  mode: StatsMode,
) {
  Column() {
    WearBarChartBody({ palette, chart, values, labels, mode })
  }
  .width('100%')
}

@Component
struct WearBarChartBody {
  @Prop palette: Palette;
  @Prop chart: ChartPalette;
  @Prop @Watch('onDataChanged') values: number[] = [];
  @Prop labels: string[] = [];
  @Prop mode: StatsMode;
  @State selected: number = -1;
  /** 出现动画进度：0 → 1，柱子从底部生长。 */
  @State private appeared: number = 0;

  /** 数据变化时重新触发生长动画。 */
  private onDataChanged(): void {
    this.appeared = 0;
    setTimeout(() => {
      this.startGrow();
    }, 50);
  }

  /** 挂载后再启动生长动画，保证首帧有 appeared=0 的起始值。 */
  private startGrow(): void {
    this.getUIContext().animateTo({ duration: 420, curve: Curve.FastOutSlowIn }, () => {
      this.appeared = 1;
    });
  }

  private max(): number {
    let max = 0;
    for (const value of this.values) {
      max = Math.max(max, value);
    }
    return max;
  }

  /** 柱子的目标高度；生长进度通过缩放绘制，不参与布局计算。 */
  private barPercent(value: number): number {
    const target = (value / this.max()) * 100;
    return Math.max(1, Math.min(100, target));
  }

  private bucketLabel(index: number): string {
    const label = index < this.labels.length ? this.labels[index] : '';
    return this.mode === StatsMode.MONTH ? `${label}日` : label;
  }

  build() {
    Column() {
      if (this.max() <= 0) {
        EmptyChart({ palette: this.palette, chart: this.chart, text: '该期间暂无佩戴记录' })
      } else {
        Row() {
          Text(this.selected >= 0
            ? `${this.bucketLabel(this.selected)} · ${formatDuration(this.values[this.selected])}`
            : '点按柱子查看数值')
            .fontSize(12)
            .fontColor(this.chart.secondary)
          Blank()
          Text(`最高 ${formatDurationShort(this.max())}`)
            .fontSize(12)
            .fontColor(this.chart.muted)
        }
        .width('100%')
        .margin({ bottom: 8 })

        Row({ space: 2 }) {
          ForEach(this.values, (value: number, index: number) => {
            Column() {
              Blank()
              if (value > 0) {
                Column()
                  .width('70%')
                  .height(`${this.barPercent(value)}%`)
                  .backgroundColor(this.chart.series1)
                  .opacity(this.selected < 0 || this.selected === index ? 1 : 0.35)
                  .scale({ x: 1, y: this.appeared, centerY: '100%' })
                  .borderRadius({ topLeft: 2, topRight: 2 })
              }
            }
            .layoutWeight(1)
            .height('100%')
            .justifyContent(FlexAlign.End)
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => {
              this.getUIContext().animateTo({ duration: 200, curve: Curve.EaseOut }, () => {
                this.selected = this.selected === index ? -1 : index;
              });
            })
          }, (value: number, index: number) => `wear-${index}`)
        }
        .width('100%')
        .height(160)
        .alignItems(VerticalAlign.Bottom)

        Divider()
          .strokeWidth(1)
          .color(this.chart.baseline)

        XAxisLabels({ labels: this.labels, color: this.chart.muted })
      }
    }
    .width('100%')
    .onAppear(() => this.startGrow())
  }
}

@Builder
export function EventStackedBarChart(
  palette: Palette,
  chart: ChartPalette,
  eventStats: EventTypeStat[],
  labels: string[],
  mode: StatsMode,
) {
  EventStackedBarChartBody({ palette, chart, eventStats, labels, mode })
}

@Component
struct EventStackedBarChartBody {
  @Prop palette: Palette;
  @Prop chart: ChartPalette;
  @Prop @Watch('onDataChanged') eventStats: EventTypeStat[] = [];
  @Prop labels: string[] = [];
  @Prop mode: StatsMode;
  @State selected: number = -1;
  /** 出现动画进度：0 → 1，柱子从底部生长。 */
  @State private appeared: number = 0;

  /** 数据变化时重新触发生长动画。 */
  private onDataChanged(): void {
    this.appeared = 0;
    setTimeout(() => {
      this.startGrow();
    }, 50);
  }

  /** 挂载后再启动生长动画，保证首帧有 appeared=0 的起始值。 */
  private startGrow(): void {
    this.getUIContext().animateTo({ duration: 420, curve: Curve.FastOutSlowIn }, () => {
      this.appeared = 1;
    });
  }

  private totals(): number[] {
    const totals: number[] = [];
    for (let index = 0; index < this.labels.length; index++) {
      let sum = 0;
      for (const stat of this.eventStats) {
        sum += index < stat.buckets.length ? stat.buckets[index] : 0;
      }
      totals.push(sum);
    }
    return totals;
  }

  private max(): number {
    let max = 0;
    for (const value of this.totals()) {
      max = Math.max(max, value);
    }
    return max;
  }

  /** 柱子的目标高度；生长进度通过缩放绘制，不参与布局计算。 */
  private barPercent(index: number): number {
    const target = (this.totals()[index] / this.max()) * 100;
    return Math.max(2, Math.min(100, target));
  }

  private bucketLabel(index: number): string {
    const label = index < this.labels.length ? this.labels[index] : '';
    return this.mode === StatsMode.MONTH ? `${label}日` : label;
  }

  private description(index: number): string {
    const parts: string[] = [];
    for (const stat of this.eventStats) {
      const value = index < stat.buckets.length ? stat.buckets[index] : 0;
      if (value > 0) {
        parts.push(`${stat.type.name} ${value} 次`);
      }
    }
    return parts.length === 0 ? '无事件' : parts.join('，');
  }

  build() {
    Column() {
      if (this.max() <= 0) {
        EmptyChart({ palette: this.palette, chart: this.chart, text: '该期间暂无事件记录' })
      } else {
        Row() {
          Text(this.selected >= 0
            ? `${this.bucketLabel(this.selected)} · ${this.description(this.selected)}`
            : '点按柱子查看数值')
            .fontSize(12)
            .fontColor(this.chart.secondary)
            .layoutWeight(1)
          Text(`最高 ${this.max()} 次`)
            .fontSize(12)
            .fontColor(this.chart.muted)
        }
        .width('100%')
        .margin({ bottom: 8 })

        Row({ space: 2 }) {
          ForEach(this.labels, (label: string, index: number) => {
            Column() {
              Blank()
              if (this.totals()[index] > 0) {
                Column() {
                  Blank()
                  ForEach(this.eventStats, (stat: EventTypeStat) => {
                    if (index < stat.buckets.length && stat.buckets[index] > 0) {
                      Column()
                        .width('100%')
                        .height(`${(stat.buckets[index] / this.totals()[index]) * 100}%`)
                        .backgroundColor(colorString(stat.type.colorArgb))
                        .opacity(this.selected < 0 || this.selected === index ? 1 : 0.35)
                    }
                  }, (stat: EventTypeStat) => `stack-${index}-${stat.type.typeKey}`)
                }
                .width('70%')
                .height(`${this.barPercent(index)}%`)
                .justifyContent(FlexAlign.End)
                .scale({ x: 1, y: this.appeared, centerY: '100%' })
                .clip(true)
                .borderRadius({ topLeft: 2, topRight: 2 })
              }
            }
            .layoutWeight(1)
            .height('100%')
            .justifyContent(FlexAlign.End)
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => {
              this.getUIContext().animateTo({ duration: 200, curve: Curve.EaseOut }, () => {
                this.selected = this.selected === index ? -1 : index;
              });
            })
          }, (label: string, index: number) => `event-${index}`)
        }
        .width('100%')
        .height(160)
        .alignItems(VerticalAlign.Bottom)

        Divider()
          .strokeWidth(1)
          .color(this.chart.baseline)

        XAxisLabels({ labels: this.labels, color: this.chart.muted })
      }
    }
    .width('100%')
    .onAppear(() => this.startGrow())
  }
}

@Component
struct XAxisLabels {
  @Prop labels: string[] = [];
  @Prop color: string;

  build() {
    Row({ space: 2 }) {
      ForEach(this.labels, (label: string, index: number) => {
        Text(this.show(index) ? label : '')
          .layoutWeight(1)
          .fontSize(9)
          .fontColor(this.color)
          .textAlign(TextAlign.Center)
          .maxLines(1)
      }, (label: string, index: number) => `axis-${index}`)
    }
    .width('100%')
    .margin({ top: 4 })
  }

  /** 超过 15 个刻度时，每 5 个显示一个，避免文字挤在一起。 */
  private show(index: number): boolean {
    if (this.labels.length <= 15) {
      return true;
    }
    return index === 0 || (index + 1) % 5 === 0;
  }
}

@Component
struct EmptyChart {
  @Prop palette: Palette;
  @Prop chart: ChartPalette;
  @Prop text: string;

  build() {
    Row() {
      Text(this.text)
        .fontSize(14)
        .fontColor(this.chart.muted)
    }
    .width('100%')
    .height(120)
    .justifyContent(FlexAlign.Center)
  }
}

/** 图表图例：只列出本期有事件的类型。 */
@Builder
export function EventChartLegend(palette: Palette, types: EventTypeStat[]) {
  Flex({ wrap: FlexWrap.Wrap }) {
    ForEach(types.filter((stat: EventTypeStat) => stat.count > 0), (stat: EventTypeStat) => {
      Row({ space: 6 }) {
        Row()
          .width(10)
          .height(10)
          .borderRadius(5)
          .backgroundColor(colorString(stat.type.colorArgb))
        Text(stat.type.name)
          .fontSize(12)
          .fontColor(palette.onSurfaceVariant)
          .margin({ right: 16 })
      }
      .alignItems(VerticalAlign.Center)
    }, (stat: EventTypeStat) => stat.type.typeKey)
  }
  .width('100%')
}
