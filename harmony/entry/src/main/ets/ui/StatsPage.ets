import { Palette, ChartPalette } from '../common/Palette';
import { AppState } from './AppState';
import {
  EventRecord,
  EventTypeEntity,
  Person,
  WearSegment,
} from '../data/Models';
import {
  computeStats,
  emptyStats,
  EventTypeStat,
  SegmentDetail,
  StatsMode,
  StatsResult,
} from '../domain/Stats';
import { YearMonth } from '../common/Dates';
import {
  formatDate,
  formatDateTime,
  formatDuration,
  formatMonth,
  formatTime,
} from '../common/TimeFormat';
import { colorString, EventColors } from '../common/EventColors';
import { image } from '@kit.ImageKit';
import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { PhotoProcessor } from '../common/PhotoProcessor';
import { recognizeNumberCandidates } from '../common/NumberOcr';
import { PhotoSource, PickedPhoto } from './components/PhotoSource';
import { fileUri } from '@kit.CoreFileKit';
import { FileUtil } from '../common/FileUtil';
import { CalendarPhotoStore, MonthCalendar, DayTimeline, TimelineEvent, TimelineSegment } from './components/Calendar';
import { EventStackedBarChart, EventChartLegend, WearBarChart } from './components/Charts';
import { PersonSelector } from './components/Selectors';
import { EmptyStateHint } from './components/Common';
import { tapFeedback } from '../common/Haptics';

/**
 * 统计页：月视图是月历，年视图是柱状图；下方是分段明细与事件明细。
 * 所有查询只取当前周期内的数据，避免把整个人物的历史读进内存。
 */
@Component
export struct StatsPage {
  @Prop palette: Palette;
  /** 页签是否正显示本页；由根页面在切换时更新，用于触发数据刷新。 */
  @Prop @Watch('onVisibleChanged') visible: boolean = false;
  @Prop reduceMotion: boolean = false;
  @Prop @Watch('onRefreshRequested') refreshToken: number = 0;
  @Prop chart: ChartPalette;
  @ObjectLink state: AppState;
  onEditSegment: (segment: WearSegment) => void = () => {
  };
  onAddSegment: () => void = () => {
  };
  onEditEvent: (event: EventRecord) => void = () => {
  };
  onDeleteSegment: (segment: WearSegment) => void = () => {
  };
  onDeleteEvent: (event: EventRecord) => void = () => {
  };

  @State private stats: StatsResult = emptyStats([], StatsMode.MONTH, YearMonth.now());
  /** 当月照片缩略图仓库；换月时整体重建以触发子组件刷新。 */
  @State private photoStore: CalendarPhotoStore = new CalendarPhotoStore();
  @State private numbers: Map<number, string> = new Map();
  @State private selectedDay: number = 0;
  @State private now: number = Date.now();
  /** 月历/周期滑动：横向位移与透明度。 */
  @State private calShift: number = 0;
  @State private calAlpha: number = 1;
  @State private loading: boolean = false;
  @State private periodChanging: boolean = false;
  /** 分段明细是否展开 */
  @State private segmentsExpanded: boolean = true;
  /** 事件明细是否展开 */
  @State private eventsExpanded: boolean = true;
  @State private segmentsBodyHeight: number = 0;
  @State private eventsBodyHeight: number = 0;
  private reloadGeneration: number = 0;
  private periodDirection: number = 1;

  aboutToAppear(): void {
    this.reload();
  }

  aboutToDisappear(): void {
    // 供根页面判空
  }

  /**
   * 页签切回本页时调用。
   * Swiper 会缓存相邻页面，组件不会重新挂载，所以数据要显式刷新一次，
   * 否则在主页锁上/开锁后切到统计，看到的还是旧数字。
   */
  refreshOnShow(): void {
    this.now = Date.now();
    this.reload();
  }

  /** 页签切到本页时刷新：Swiper 缓存页面不会重新挂载。 */
  onVisibleChanged(): void {
    if (this.visible) {
      this.refreshOnShow();
    }
  }

  onRefreshRequested(): void {
    if (this.visible) {
      this.now = Date.now();
      this.reload();
    }
  }

  private toggleSegments(): void {
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 220, curve: Curve.EaseOut }, () => {
      this.segmentsExpanded = !this.segmentsExpanded;
    });
  }

  private toggleEvents(): void {
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 220, curve: Curve.EaseOut }, () => {
      this.eventsExpanded = !this.eventsExpanded;
    });
  }

  aboutToReuse(params: Record<string, Object>): void {
    this.reload();
  }

  /** 周期或人物变化时重新取数。 */
  onPeriodChanged(): void {
    this.reload();
  }

  /**
   * 切换上/下一期：旧内容滑出淡出，数据加载完成后从另一侧滑入淡入。
   * 月视图与年视图都走同一个动效，方向由 delta 决定。
   * 使用 onFinish 回调确保动画与数据加载顺序正确。
   */
  private changePeriod(delta: number): void {
    if (this.periodChanging) {
      return;
    }
    this.periodChanging = true;
    this.periodDirection = delta;

    // 先播放退场动画，动画结束后再加载数据
    animateTo({
      duration: 200,
      curve: Curve.EaseIn,
      onFinish: () => {
        this.state.movePeriod(delta);
        this.reload().then(() => {
          // 数据加载完成后播放进场动画
          this.finishPeriodChange();
        }).catch(() => {
          // 加载失败，直接恢复显示
          this.calAlpha = 1;
          this.calShift = 0;
          this.periodChanging = false;
        });
      }
    }, () => {
      this.calAlpha = 0;
      this.calShift = -28 * delta;
    });
  }

  /** 最新一次取数结束后恢复内容，成功和失败都不能把页面留在透明状态。 */
  private finishPeriodChange(): void {
    if (!this.periodChanging) {
      return;
    }
    const direction = this.periodDirection;
    this.calShift = 28 * direction;
    animateTo({
      duration: 260,
      curve: Curve.EaseOut,
      onFinish: () => {
        this.periodChanging = false;
      }
    }, () => {
      this.calAlpha = 1;
      this.calShift = 0;
    });
  }

  private async reload(): Promise<void> {
    const generation = ++this.reloadGeneration;
    const person = this.state.currentPerson;
    if (person === null) {
      this.segmentsBodyHeight = 0;
      this.eventsBodyHeight = 0;
      this.stats = emptyStats(this.state.eventTypes, this.state.statsMode, this.state.statsAnchor);
      this.loading = false;
      this.finishPeriodChange();
      return;
    }
    this.loading = true;
    try {
      const mode = this.state.statsMode;
      const anchor = this.state.statsAnchor;
      const from = mode === StatsMode.MONTH
        ? anchor.startMillis()
        : new Date(anchor.year, 0, 1).getTime();
      const to = mode === StatsMode.MONTH
        ? anchor.endMillis()
        : new Date(anchor.year + 1, 0, 1).getTime();
      const segments = await this.state.getSegmentsOverlapping(person.id, from, to);
      const events = await this.state.getEventsInRange(person.id, from, to);
      const nextStats = computeStats(
        segments,
        events,
        this.state.eventTypes,
        mode,
        anchor,
        this.now,
      );
      if (generation !== this.reloadGeneration) {
        return;
      }
      this.segmentsBodyHeight = 0;
      this.eventsBodyHeight = 0;
      this.stats = nextStats;
      if (mode === StatsMode.MONTH) {
        await this.loadMonthDecorations(person.id, anchor, generation);
      } else {
        this.photoStore = new CalendarPhotoStore();
        this.numbers = new Map();
      }
    } finally {
      if (generation === this.reloadGeneration) {
        this.loading = false;
        this.finishPeriodChange();
      }
    }
  }

  /** 月历格子上的照片与当日随机数。 */
  private async loadMonthDecorations(personId: number, anchor: YearMonth, generation: number): Promise<void> {
    const fromDay = anchor.firstEpochDay();
    const toDay = anchor.lastEpochDay();
    const store = new CalendarPhotoStore();
    const photos = await this.state.getDailyPhotosInRange(personId, fromDay, toDay);
    for (const photo of photos) {
      const day = Math.round((photo.epochDay - fromDay) + 1);
      if (day >= 1 && day <= anchor.lengthOfMonth()) {
        const pixelMap = await PhotoProcessor.decodeScaled(photo.jpeg, 160);
        if (pixelMap !== null) {
          store.set(day, pixelMap);
        }
      }
    }
    const numbers = await this.state.getDailyRandomNumbersInRange(personId, fromDay, toDay);
    const numberMap = new Map<number, string>();
    for (const entry of numbers) {
      const day = Math.round((entry.epochDay - fromDay) + 1);
      numberMap.set(day, entry.number);
    }
    if (generation !== this.reloadGeneration) {
      return;
    }
    // 整体替换而不是改内部：@Observed 只跟踪引用变化
    this.photoStore = store;
    this.numbers = numberMap;
  }

  private groupedEvents(): Map<string, EventRecord[]> {
    const grouped = new Map<string, EventRecord[]>();
    for (const event of this.stats.eventDetails) {
      const key = formatDate(event.occurredAt);
      const list = grouped.get(key) ?? [];
      list.push(event);
      grouped.set(key, list);
    }
    return grouped;
  }

  private typeByKey(): Map<string, EventTypeEntity> {
    const map = new Map<string, EventTypeEntity>();
    for (const type of this.state.eventTypes) {
      map.set(type.typeKey, type);
    }
    return map;
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Scroll() {
        Column({ space: 12 }) {
          Text('统计')
            .fontSize(28)
            .fontFamily('HarmonyOS Sans')
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onSurface)
            .width('100%')

          PersonSelector({
            palette: this.palette,
            persons: this.state.persons,
            selectedPersonId: this.state.currentPerson === null ? -1 : this.state.currentPerson!.id,
            onSelect: (person: Person) => {
              this.state.selectPerson(person).then(() => this.reload());
            },
          })

          // 周期切换：月 / 年 + 回到本期
          Row({ space: 8 }) {
            this.ModeChip('月', StatsMode.MONTH)
            this.ModeChip('年', StatsMode.YEAR)
            Blank()
            if (!this.state.statsAnchor.equals(YearMonth.now())) {
              Row({ space: 4 }) {
                Text('回到本期')
                  .fontSize(13)
                  .fontColor(this.palette.onPrimaryContainer)
              }
              .padding({ left: 12, right: 12, top: 8, bottom: 8 })
              .borderRadius(20)
              .backgroundColor(this.palette.primaryContainer)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => {
                this.state.goToCurrentPeriod();
                this.reload();
              })
              // 出现时淡入 + 从右端横向展开（对齐 Android 的 expandHorizontally），消失时淡出 + 收缩
              .transition(TransitionEffect.asymmetric(
                TransitionEffect.OPACITY
                  .combine(TransitionEffect.scale({ x: 0.8, y: 1, centerX: '100%', centerY: '50%' }))
                  .animation({ duration: 220, curve: Curve.EaseOut }),
                TransitionEffect.OPACITY
                  .combine(TransitionEffect.scale({ x: 0.8, y: 1, centerX: '100%', centerY: '50%' }))
                  .animation({ duration: 160, curve: Curve.EaseIn }),
              ))
            }
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)

          Row() {
            Text('‹')
              .fontSize(26)
              .fontColor(this.palette.onSurface)
              .width(44)
              .textAlign(TextAlign.Center)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => {
                this.changePeriod(-1);
              })
            Text(this.state.statsMode === StatsMode.MONTH
              ? formatMonth(this.state.statsAnchor)
              : `${this.state.statsAnchor.year}年`)
              .fontSize(18)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.palette.onSurface)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .translate({ x: this.calShift })
              .opacity(this.calAlpha)
            Text('›')
              .fontSize(26)
              .fontColor(this.palette.onSurface)
              .width(44)
              .textAlign(TextAlign.Center)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => {
                this.changePeriod(1);
              })
          }
          .width('100%')

          this.SummaryCard()

          Column() {
            if (this.state.statsMode === StatsMode.MONTH) {
              this.CalendarCard()
            } else {
              this.WearChartCard()
              this.EventChartCard()
            }
          }
          .width('100%')
          .translate({ x: this.calShift })
          .opacity(this.calAlpha)

          // 分段明细
          Column() {
            Row() {
              Blank()
              Button('补录')
                .width(76)
                .height(44)
                .fontSize(14)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.palette.primary)
                .backgroundColor(this.palette.surfaceContainerHigh)
                .borderWidth(1)
                .borderColor(this.palette.outlineVariant)
                .borderRadius(22)
                .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
                .enabled(this.state.currentPerson !== null)
                .onClick(() => this.onAddSegment())
            }
            .width('100%')
            .margin({ bottom: 8 })

            Row() {
              Text('分段明细')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.palette.onSurface)
              Blank()
              DetailChevron({
                palette: this.palette,
                expanded: this.segmentsExpanded,
                reduceMotion: this.reduceMotion,
              })
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 12, bottom: 12 })
            .borderRadius(12)
            .backgroundColor(this.palette.surfaceContainerLow)
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => this.toggleSegments())

            Stack({ alignContent: Alignment.TopStart }) {
              Column({ space: 8 }) {
                if (this.stats.segmentDetails.length === 0) {
                  EmptyStateHint(this.palette, '该期间没有分段记录')
                } else {
                  ForEach(this.stats.segmentDetails, (detail: SegmentDetail) => {
                    this.SegmentCard(detail)
                  }, (detail: SegmentDetail) => `seg-${this.palette.dark}-${detail.segment.id}`)
                }
              }
              .width('100%')
              .padding({ top: 8 })
              .onAreaChange((_oldValue: Area, newValue: Area) => {
                const height = newValue.height as number;
                if (height > 0 && Math.abs(height - this.segmentsBodyHeight) > 0.5) {
                  this.segmentsBodyHeight = height;
                }
              })
            }
            .width('100%')
            .height(this.segmentsExpanded ? (this.segmentsBodyHeight > 0 ? this.segmentsBodyHeight : 'auto') : 0)
            .opacity(this.segmentsExpanded ? 1 : 0)
            .clip(true)
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)

          // 事件明细
          Column() {
            Row() {
              Text('事件明细')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.palette.onSurface)
              Blank()
              DetailChevron({
                palette: this.palette,
                expanded: this.eventsExpanded,
                reduceMotion: this.reduceMotion,
              })
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 12, bottom: 12 })
            .borderRadius(12)
            .backgroundColor(this.palette.surfaceContainerLow)
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => this.toggleEvents())

            Stack({ alignContent: Alignment.TopStart }) {
              Column({ space: 8 }) {
                if (this.stats.eventDetails.length === 0) {
                  EmptyStateHint(this.palette, '该期间没有事件记录')
                } else {
                  ForEach(Array.from(this.groupedEvents().entries()), (entry: [string, EventRecord[]]) => {
                    Column({ space: 8 }) {
                      Text(entry[0])
                        .fontSize(12)
                        .fontColor(this.palette.onSurfaceVariant)
                        .width('100%')
                        .margin({ top: 4 })
                      ForEach(entry[1], (event: EventRecord) => {
                        this.EventCard(event)
                      }, (event: EventRecord) => `evt-${event.id}`)
                    }
                    .width('100%')
                    .alignItems(HorizontalAlign.Start)
                  }, (entry: [string, EventRecord[]]) => `date-${entry[0]}`)
                }
              }
              .width('100%')
              .padding({ top: 8 })
              .onAreaChange((_oldValue: Area, newValue: Area) => {
                const height = newValue.height as number;
                if (height > 0 && Math.abs(height - this.eventsBodyHeight) > 0.5) {
                  this.eventsBodyHeight = height;
                }
              })
            }
            .width('100%')
            .height(this.eventsExpanded ? (this.eventsBodyHeight > 0 ? this.eventsBodyHeight : 'auto') : 0)
            .opacity(this.eventsExpanded ? 1 : 0)
            .clip(true)
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 16, bottom: 120 })
      }
      .scrollBar(BarState.Off)
      .width('100%')
      .height('100%')
      .backgroundColor(this.palette.background)

      if (this.selectedDay > 0) {
        DayDetailDialog({
          palette: this.palette,
          state: this.state,
          anchor: this.state.statsAnchor,
          day: this.selectedDay,
          stats: this.stats,
          numbers: this.numbers,
          onDismiss: () => {
            this.selectedDay = 0;
            this.reload();
          },
        })
      }
    }
  }

  @Builder
  ModeChip(label: string, mode: StatsMode) {
    Text(label)
      .fontSize(13)
      .fontColor(this.state.statsMode === mode
        ? this.palette.onSecondaryContainer
        : this.palette.onSurfaceVariant)
      .padding({ left: 16, right: 16, top: 6, bottom: 6 })
      .borderRadius(16)
      .borderWidth(1)
      .borderColor(this.state.statsMode === mode ? '#00000000' : this.palette.outline)
      .backgroundColor(this.state.statsMode === mode
        ? this.palette.secondaryContainer
        : Color.Transparent)
      .backgroundEffect({ radius: 14, saturation: 1, color: this.palette.dark ? '#A82A2A2A' : '#B8FFFFFF' })
      .linearGradient({ angle: 135, colors: [
        ['#55FFFFFF', 0], ['#14FFFFFF', 0.32],
        ['#05FFFFFF', 0.68], ['#28FFFFFF', 1],
      ] })
      .shadow({ radius: 7, color: '#14000000', offsetY: 1 })
      .onTouch((event: TouchEvent) => {
        if (event.type === TouchType.Down) {
          tapFeedback();
        }
      })
      .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
      .onClick(() => {
        this.state.setStatsMode(mode);
        this.reload();
      })
  }

  @Builder
  SummaryCard() {
    Column() {
      Text('总佩戴时长')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
      Text(formatDuration(this.stats.totalWearMillis))
        .fontSize(26)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.palette.onSurface)
        .width('100%')
        .margin({ top: 4 })
        .transition(TransitionEffect.OPACITY.animation({ duration: 200 }))
        // 数值变化时平滑过渡，避免跳变
        .animation({ duration: 250, curve: Curve.EaseOut })

      if (this.stats.eventStats.filter((s: EventTypeStat) => s.count > 0).length === 0) {
        Text('本期没有事件记录')
          .fontSize(14)
          .fontColor(this.palette.onSurfaceVariant)
          .width('100%')
          .margin({ top: 16 })
      } else {
        Column({ space: 12 }) {
          ForEach(this.stats.eventStats.filter((s: EventTypeStat) => s.count > 0), (stat: EventTypeStat) => {
            Row() {
              Row({ space: 6 }) {
                Row()
                  .width(8)
                  .height(8)
                  .borderRadius(4)
                  .backgroundColor(colorString(stat.type.colorArgb))
                Text(stat.type.name)
                  .fontSize(12)
                  .fontColor(this.palette.onSurfaceVariant)
              }
              .layoutWeight(1)
              .alignItems(VerticalAlign.Center)
              Text(`${stat.count} 次`)
                .fontSize(17)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.palette.onSurface)
            }
            .width('100%')
          }, (stat: EventTypeStat) => `sum-${stat.type.typeKey}`)
        }
        .width('100%')
        .margin({ top: 16 })
      }
    }
    .width('100%')
    .padding(20)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
  }

  @Builder
  CalendarCard() {
    Column() {
      Row() {
        Text('月历')
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.palette.onSurface)
        Blank()
        Text('按天')
          .fontSize(12)
          .fontColor(this.palette.onSurfaceVariant)
      }
      .width('100%')
      .margin({ bottom: 12 })

      MonthCalendar({
        palette: this.palette,
        chart: this.chart,
        anchor: this.state.statsAnchor,
        dayWear: this.stats.wearBuckets,
        eventStats: this.stats.eventStats,
        photoStore: this.photoStore,
        onDayClick: (day: number) => {
          this.selectedDay = day;
        },
      })

      EventChartLegend(this.palette, this.stats.eventStats)

      Text('颜色越深，当天佩戴越久；点按日期查看明细、上传当天照片。')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
        .margin({ top: 8 })
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
    .animation({ duration: 250, curve: Curve.EaseOut })
  }

  @Builder
  CardHeader(title: string, subtitle: string) {
    Row() {
      Text(title)
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.palette.onSurface)
      Blank()
      Text(subtitle)
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
    }
    .width('100%')
    .margin({ bottom: 12 })
  }

  @Builder
  WearChartCard() {
    Column() {
      this.CardHeader('佩戴时长', '按月')
      WearBarChart(
        this.palette,
        this.chart,
        this.stats.wearBuckets,
        this.stats.bucketLabels,
        this.state.statsMode,
      )
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
  }

  @Builder
  EventChartCard() {
    Column() {
      this.CardHeader('事件次数', '按月')
      EventStackedBarChart(
        this.palette,
        this.chart,
        this.stats.eventStats,
        this.stats.bucketLabels,
        this.state.statsMode,
      )
      EventChartLegend(this.palette, this.stats.eventStats)
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
  }

  @Builder
  SegmentCard(detail: SegmentDetail) {
    Column({ space: 6 }) {
      this.DetailLine('戴上', formatDateTime(detail.segment.startAt), this.palette.onSurface)
      this.DetailLine(
        '取下',
        detail.segment.endAt === null ? '佩戴中' : formatDateTime(detail.segment.endAt),
        detail.segment.endAt === null ? this.palette.primary : this.palette.onSurface,
      )
      this.DetailLine(
        '时长',
        formatDuration((detail.segment.endAt === null ? this.now : detail.segment.endAt) -
          detail.segment.startAt),
        this.palette.onSurface,
      )
      if (detail.crossesPeriodBoundary) {
        Text(`本期计入 ${formatDuration(detail.clippedMillis)}；明细显示完整分段`)
          .fontSize(12)
          .fontColor(this.palette.onSurfaceVariant)
      }
    }
    .width('100%')
    .padding(14)
    .backgroundColor(this.palette.surface)
    .borderRadius(8)
    .borderWidth(1)
    .borderColor(this.palette.outlineVariant)
    .alignItems(HorizontalAlign.Start)
    .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
    .onClick(() => this.onEditSegment(detail.segment))
    .gesture(
      LongPressGesture({ repeat: false, duration: 400 })
        .onAction(() => {
          tapFeedback();
          this.onDeleteSegment(detail.segment);
        }),
    )
  }

  @Builder
  DetailLine(label: string, value: string, valueColor: string) {
    Row() {
      Text(label)
        .fontSize(12)
        .width(44)
        .fontColor(this.palette.onSurfaceVariant)
      Text(value)
        .fontSize(15)
        .fontWeight(label === '时长' ? FontWeight.Bold : FontWeight.Medium)
        .layoutWeight(1)
        .fontColor(valueColor)
    }
    .width('100%')
    .alignItems(VerticalAlign.Top)
  }

  @Builder
  EventCard(event: EventRecord) {
    Row({ space: 10 }) {
      Row()
        .width(10)
        .height(10)
        .borderRadius(5)
        .backgroundColor(colorString(
          this.typeByKey().get(event.type)?.colorArgb ?? EventColors.SLATE,
        ))
      Column() {
        Text(this.typeByKey().get(event.type)?.name ?? '未知事件')
          .fontSize(16)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.palette.onSurface)
          .width('100%')
        if (event.note !== null && event.note.length > 0) {
          Text(event.note)
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
        }
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
      Text(formatTime(event.occurredAt))
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
    .alignItems(VerticalAlign.Center)
    .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
    .onClick(() => this.onEditEvent(event))
    .gesture(
      LongPressGesture({ repeat: false, duration: 400 })
        .onAction(() => {
          tapFeedback();
          this.onDeleteEvent(event);
        }),
    )
  }
}

@Component
struct DetailChevron {
  @Prop palette: Palette;
  @Prop @Watch('onExpandedChanged') expanded: boolean = true;
  @Prop reduceMotion: boolean = false;
  @State private angle: number = 180;

  aboutToAppear(): void {
    this.angle = this.expanded ? 180 : 0;
  }

  onExpandedChanged(): void {
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 220, curve: Curve.EaseInOut }, () => {
      this.angle = this.expanded ? 180 : 0;
    });
  }

  private path(): string {
    const unit = vp2px(1);
    return `M${3 * unit} ${5 * unit} L${7 * unit} ${9 * unit} L${11 * unit} ${5 * unit}`;
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Path({ width: 14, height: 14, commands: this.path() })
        .fillOpacity(0)
        .stroke(this.palette.primary)
        .strokeWidth(2)
        .strokeLineCap(LineCapStyle.Round)
        .strokeLineJoin(LineJoinStyle.Round)
        .rotate({ angle: this.angle })
    }
    .width(30)
    .height(30)
    .borderRadius(15)
    .backgroundColor(this.palette.surfaceContainerHigh)
    .borderWidth(1)
    .borderColor(this.palette.outlineVariant)
    .clip(true)
  }
}

/**
 * 日详情：当天佩戴时长、随机数、时间轴、事件次数、照片、分段与事件列表。
 */
@Component
export struct DayDetailDialog {
  @Prop palette: Palette;
  @ObjectLink state: AppState;
  @Prop anchor: YearMonth;
  @Prop day: number;
  @Prop stats: StatsResult;
  @Prop numbers: Map<number, string>;
  onDismiss: () => void = () => {
  };

  @State private photo: image.PixelMap | undefined = undefined;
  @State private photoBytes: Uint8Array | null = null;
  @State private videoUri: string = '';
  @State private hasMovingVideo: boolean = false;
  @State private playbackError: boolean = false;
  @State private videoError: string = '';
  private videoController: VideoController = new VideoController();
  @State private ocrResult: string = '';
  @State private ocrRunning: boolean = false;
  @State private uploading: boolean = false;
  /** 入场进度：0 → 1，驱动遮罩渐显与卡片缩放。 */
  @State private appear: number = 0;
  /** 正在播放退场动画：期间忽略重复点击。 */
  @State private closing: boolean = false;
  /** 是否显示全屏照片。 */
  @State private showFullPhoto: boolean = false;
  /** 全屏照片进场进度：0 → 1。 */
  @State private photoAppear: number = 0;

  aboutToAppear(): void {
    this.loadPhoto();
  }

  /** 先播退场动画，再通知外层移除弹窗。 */
  private dismiss(): void {
    if (this.closing) {
      return;
    }
    this.closing = true;
    animateTo({ duration: 160, curve: Curve.EaseIn }, () => {
      this.appear = 0;
    });
    setTimeout(() => {
      this.onDismiss();
    }, 160);
  }

  private epochDay(): number {
    return this.anchor.epochDayOf(this.day);
  }

  private dayStart(): number {
    return new Date(this.anchor.year, this.anchor.month - 1, this.day).getTime();
  }

  private dayEnd(): number {
    return new Date(this.anchor.year, this.anchor.month - 1, this.day + 1).getTime();
  }

  private isFuture(): boolean {
    const now = new Date();
    const target = new Date(this.anchor.year, this.anchor.month - 1, this.day);
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return target.getTime() > today.getTime();
  }

  private async loadPhoto(): Promise<void> {
    const person = this.state.currentPerson;
    if (person === null) {
      return;
    }
    const photo = await this.state.getDailyPhoto(person.id, this.epochDay());
    if (photo === null) {
      this.photo = undefined;
      this.photoBytes = null;
      this.videoUri = '';
      this.hasMovingVideo = false;
      this.videoError = '';
      return;
    }
    this.photoBytes = photo.jpeg;
    this.hasMovingVideo = photo.videoFingerprint.length > 0;
    let decoded = await PhotoProcessor.decodeScaled(photo.jpeg, 720);
    if (decoded === null) {
      decoded = await PhotoProcessor.decodeScaled(photo.jpeg, 160);
    }
    this.photo = decoded === null ? undefined : decoded;
    this.videoUri = '';
    this.playbackError = false;
    this.videoError = '';
    if (photo.videoFingerprint.length > 0) {
      try {
        const video = await this.state.getDailyPhotoVideo(person.id, this.epochDay());
        if (video !== null && video.length > 0) {
          const context = getContext(this) as common.UIAbilityContext;
          const dir = FileUtil.join(context.cacheDir, 'moving-preview');
          FileUtil.ensureDir(dir);
          const path = FileUtil.join(dir, `${person.id}_${this.epochDay()}.mp4`);
          FileUtil.writeBytes(path, video);
          this.videoUri = fileUri.getUriFromPath(path);
        } else {
          this.videoError = '视频数据为空';
        }
      } catch (err) {
        this.videoUri = '';
        this.videoError = (err as Error).message ?? '视频读取失败';
      }
    }
  }

  private daySegments(): TimelineSegment[] {
    const result: TimelineSegment[] = [];
    const dayStart = this.dayStart();
    const dayEnd = this.dayEnd();
    for (const detail of this.stats.segmentDetails) {
      const start = Math.max(detail.segment.startAt, dayStart);
      const end = Math.min(detail.segment.endAt ?? dayEnd, dayEnd);
      if (end > start) {
        const item: TimelineSegment = { start: start, end: end };
        result.push(item);
      }
    }
    return result;
  }

  private dayEvents(): EventRecord[] {
    const dayStart = this.dayStart();
    const dayEnd = this.dayEnd();
    return this.stats.eventDetails
      .filter((e: EventRecord) => e.occurredAt >= dayStart && e.occurredAt < dayEnd)
      .sort((a: EventRecord, b: EventRecord) => a.occurredAt - b.occurredAt);
  }

  private timelineEvents(): TimelineEvent[] {
    const map = new Map<string, EventTypeEntity>();
    for (const type of this.state.eventTypes) {
      map.set(type.typeKey, type);
    }
    return this.dayEvents().map((event: EventRecord): TimelineEvent => {
      const item: TimelineEvent = {
        at: event.occurredAt,
        colorArgb: map.get(event.type)?.colorArgb ?? EventColors.SLATE,
      };
      return item;
    });
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#66000000')
        .opacity(this.appear)
        .onClick(() => this.dismiss())

      Scroll() {
        Column({ space: 10 }) {
          Text(`${this.anchor.year}年${this.anchor.month}月${this.day}日`)
            .fontSize(22)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onSurface)
            .width('100%')

          Text(this.state.currentPerson?.name ?? '')
            .fontSize(13)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')

          Text(`佩戴 ${formatDuration(this.stats.wearBuckets[this.day - 1] ?? 0)}`)
            .fontSize(18)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onSurface)
            .width('100%')

          if (this.numbers.has(this.day)) {
            Text(`当日随机数 ${this.numbers.get(this.day)}`)
              .fontSize(14)
              .fontFamily('HarmonyOS Sans Mono')
              .fontColor(this.palette.onSurface)
              .width('100%')
          }

          DayTimeline({
            palette: this.palette,
            dayStart: this.dayStart(),
            dayEnd: this.dayEnd(),
            segments: this.daySegments(),
            events: this.timelineEvents(),
          })

          // 当天照片
          Text('当天照片')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')

          if (this.photo !== undefined) {
            Image(this.photo)
              .width('100%')
              .height(200)
              .objectFit(ImageFit.Contain)
              .borderRadius(8)
              .backgroundColor(this.palette.surfaceContainerLow)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => {
                this.showFullPhoto = true;
              })
            if (this.hasMovingVideo) {
              Text(this.videoUri.length === 0
                ? `动态照片暂无法播放：${this.videoError}` : '动态照片 · 点击预览播放')
                .fontSize(12)
                .fontColor(this.videoUri.length === 0 ? this.palette.error : this.palette.primary)
            } else {
              Text('单帧照片')
                .fontSize(12)
                .fontColor(this.palette.onSurfaceVariant)
            }
          } else if (this.photoBytes !== null) {
            Text('照片已上传，预览暂不可用。')
              .fontSize(12)
              .fontColor(this.palette.onSurfaceVariant)
              .width('100%')
          } else if (!this.isFuture()) {
            Text('还没有上传照片。')
              .fontSize(12)
              .fontColor(this.palette.onSurfaceVariant)
              .width('100%')
          }

          if (this.isFuture()) {
            Text('今天之后的日期不能上传照片。')
              .fontSize(12)
              .fontColor(this.palette.onSurfaceVariant)
              .width('100%')
          } else {
            Row({ space: 8 }) {
              Button(this.photoBytes === null ? '上传照片' : '更换照片')
                .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
                .height(40)
                .fontSize(13)
                .fontColor(this.palette.onPrimary)
                .backgroundColor(this.palette.primary)
                .borderRadius(20)
                .enabled(!this.uploading)
                .onClick(() => this.uploadPhoto())

              if (this.photoBytes !== null) {
                Button('删除照片')
                  .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
                  .height(40)
                  .fontSize(13)
                  .fontColor(this.palette.error)
                  .backgroundColor(Color.Transparent)
                  .backgroundEffect({ radius: 14, saturation: 1, color: this.palette.dark ? '#A82A2A2A' : '#B8FFFFFF' })
                  .linearGradient({ angle: 135, colors: [
                    ['#55FFFFFF', 0], ['#14FFFFFF', 0.32],
                    ['#05FFFFFF', 0.68], ['#28FFFFFF', 1],
                  ] })
                  .shadow({ radius: 7, color: '#14000000', offsetY: 1 })
                  .onTouch((event: TouchEvent) => {
                    if (event.type === TouchType.Down) {
                      tapFeedback();
                    }
                  })
                  .onClick(() => {
                    this.state.deleteDailyPhoto(this.epochDay()).then(() => {
                      this.photo = undefined;
                      this.photoBytes = null;
                      this.videoUri = '';
                      this.hasMovingVideo = false;
                      this.ocrResult = '';
                    });
                  })

                Button(this.ocrRunning ? '识别中…' : '识别照片号码')
                  .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
                  .height(40)
                  .fontSize(13)
                  .fontColor(this.palette.primary)
                  .backgroundColor(Color.Transparent)
                  .backgroundEffect({ radius: 14, saturation: 1, color: this.palette.dark ? '#A82A2A2A' : '#B8FFFFFF' })
                  .linearGradient({ angle: 135, colors: [
                    ['#55FFFFFF', 0], ['#14FFFFFF', 0.32],
                    ['#05FFFFFF', 0.68], ['#28FFFFFF', 1],
                  ] })
                  .shadow({ radius: 7, color: '#14000000', offsetY: 1 })
                  .onTouch((event: TouchEvent) => {
                    if (event.type === TouchType.Down) {
                      tapFeedback();
                    }
                  })
                  .enabled(!this.ocrRunning)
                  .onClick(() => this.runOcr())
              }
            }
            .width('100%')
          }

          if (this.ocrResult.length > 0) {
            Text(this.ocrResult)
              .fontSize(12)
              .fontColor(this.ocrResult.startsWith('✓')
                ? this.palette.primary
                : (this.ocrResult.startsWith('✗') ? this.palette.error : this.palette.onSurfaceVariant))
              .width('100%')
          }

          // 分段
          if (this.daySegments().length > 0) {
            Text('分段')
              .fontSize(12)
              .fontColor(this.palette.onSurfaceVariant)
              .width('100%')
            ForEach(this.daySegments(), (segment: TimelineSegment, index: number) => {
              Column() {
                Text(`${formatDateTime(segment.start)} - ${formatDateTime(segment.end)}`)
                  .fontSize(12)
                  .fontColor(this.palette.onSurface)
                  .width('100%')
                Text(`当日计入 ${formatDuration(segment.end - segment.start)}`)
                  .fontSize(12)
                  .fontColor(this.palette.onSurfaceVariant)
                  .width('100%')
              }
              .width('100%')
            }, (segment: TimelineSegment, index: number) => `ds-${index}`)
          }

          // 事件
          if (this.dayEvents().length > 0) {
            Text('事件')
              .fontSize(12)
              .fontColor(this.palette.onSurfaceVariant)
              .width('100%')
            ForEach(this.dayEvents(), (event: EventRecord) => {
              Row({ space: 6 }) {
                Row()
                  .width(8)
                  .height(8)
                  .borderRadius(4)
                  .backgroundColor(colorString(
                    this.state.eventTypes.find((t: EventTypeEntity) => t.typeKey === event.type)?.colorArgb ??
                    EventColors.SLATE,
                  ))
                Text(`${formatTime(event.occurredAt)} · ` +
                  `${this.state.eventTypes.find((t: EventTypeEntity) => t.typeKey === event.type)?.name ?? '未知事件'}` +
                  `${event.note !== null && event.note.length > 0 ? ` · ${event.note}` : ''}`)
                  .fontSize(12)
                  .fontColor(this.palette.onSurface)
                  .layoutWeight(1)
              }
              .width('100%')
            }, (event: EventRecord) => `de-${event.id}`)
          }

          if (this.daySegments().length === 0 && this.dayEvents().length === 0) {
            Text('当天没有记录')
              .fontSize(14)
              .fontColor(this.palette.onSurfaceVariant)
              .width('100%')
          }

          Button('关闭')
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .width('100%')
            .height(44)
            .fontSize(15)
            .fontColor(this.palette.onSurface)
            .backgroundColor(Color.Transparent)
            .backgroundEffect({ radius: 14, saturation: 1, color: this.palette.dark ? '#A82A2A2A' : '#B8FFFFFF' })
            .linearGradient({ angle: 135, colors: [
              ['#55FFFFFF', 0], ['#14FFFFFF', 0.32],
              ['#05FFFFFF', 0.68], ['#28FFFFFF', 1],
            ] })
            .shadow({ radius: 7, color: '#14000000', offsetY: 1 })
            .onTouch((event: TouchEvent) => {
              if (event.type === TouchType.Down) {
                tapFeedback();
              }
            })
            .borderWidth(1)
            .borderColor(this.palette.dark ? '#55FFFFFF' : '#D9FFFFFF')
            .borderRadius(22)
            .margin({ top: 8 })
            .onClick(() => this.dismiss())
        }
        .width('100%')
        .padding(24)
        .backgroundColor(this.palette.surface)
        .borderRadius(24)
        .alignItems(HorizontalAlign.Start)
        .scale({ x: 0.85 + 0.15 * this.appear, y: 0.85 + 0.15 * this.appear })
        .opacity(this.appear)
      }
      .constraintSize({ maxHeight: '86%' })
      .scrollBar(BarState.Off)
      .margin({ left: 20, right: 20 })

      // 全屏照片：盖在日详情卡片之上
      if (this.showFullPhoto && this.photo !== undefined) {
        Stack({ alignContent: Alignment.Center }) {
          Column()
            .width('100%')
            .height('100%')
            .backgroundColor(Color.Black)
            .opacity(this.photoAppear)
            .onClick(() => this.closeFullPhoto())

          if (this.videoUri.length > 0) {
            Video({ src: this.videoUri, previewUri: this.photo, controller: this.videoController })
              .width('100%')
              .height('100%')
              .objectFit(ImageFit.Contain)
              .autoPlay(true)
              .muted(true)
              .controls(false)
              .loop(true)
              .padding(16)
              .opacity(this.photoAppear)
              .onClick(() => this.videoController.start())
              .onError(() => { this.playbackError = true; })
          } else {
            Image(this.photo)
              .width('100%')
              .height('100%')
              .objectFit(ImageFit.Contain)
              .padding(16)
              .scale({ x: 0.94 + 0.06 * this.photoAppear, y: 0.94 + 0.06 * this.photoAppear })
              .opacity(this.photoAppear)
              .onClick(() => this.closeFullPhoto())
          }

          if (this.playbackError) {
            Text('动态视频播放失败')
              .fontSize(14)
              .fontColor(Color.White)
              .position({ y: '75%' })
          }

          Text('✕')
            .fontSize(24)
            .fontColor(Color.White)
            .width(44)
            .height(44)
            .textAlign(TextAlign.Center)
            .position({ x: '88%', y: 24 })
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => this.closeFullPhoto())
        }
        .width('100%')
        .height('100%')
        .onAppear(() => {
          animateTo({ duration: 220, curve: Curve.Friction }, () => {
            this.photoAppear = 1;
          });
        })
      }
    }
    .width('100%')
    .height('100%')
    .alignContent(Alignment.Center)
    .onAppear(() => {
      // 等节点挂载后再动画：首帧已有 appear=0 的旧值，动画才看得出效果
      animateTo({ duration: 260, curve: Curve.Friction }, () => {
        this.appear = 1;
      });
    })
  }

  /** 关闭全屏照片：先播退场再移除。 */
  private closeFullPhoto(): void {
    this.videoController.stop();
    animateTo({ duration: 160, curve: Curve.EaseIn }, () => {
      this.photoAppear = 0;
    });
    setTimeout(() => {
      this.showFullPhoto = false;
      this.photoAppear = 0;
    }, 160);
  }

  private uploadPhoto(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.uploading = true;
    PhotoSource.pick(context, context.cacheDir).then((picked: PickedPhoto | null) => {
      this.uploading = false;
      if (picked === null) {
        return;
      }
      this.state.setDailyPhoto(this.epochDay(), picked.jpeg, picked.video).then(() => this.loadPhoto());
    }).catch((err: Error) => {
      this.uploading = false;
      this.state.notify(err.message ?? '上传照片失败');
    });
  }

  private runOcr(): void {
    if (this.photoBytes === null) {
      return;
    }
    this.ocrRunning = true;
    recognizeNumberCandidates(this.photoBytes).then((candidates) => {
      const number = this.numbers.get(this.day);
      if (number === undefined) {
        this.ocrResult = '当天没有随机数可对照';
      } else if (candidates.indexOf(number) >= 0) {
        this.ocrResult = `✓ 照片号码与当日随机数 ${number} 一致`;
      } else if (candidates.length === 0) {
        this.ocrResult = '未在照片中识别到 5 位号码';
      } else {
        this.ocrResult = `✗ 识别到 ${candidates.slice(0, 3).join('、')}，与当日 ${number} 不一致`;
      }
    }).finally(() => {
      this.ocrRunning = false;
    });
  }
}
