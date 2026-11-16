import { common } from '@kit.AbilityKit';
import { ConfigurationConstant } from '@kit.AbilityKit';
import { curves, promptAction } from '@kit.ArkUI';
import { settings } from '@kit.BasicServicesKit';
import { ThemeMode } from '../common/ThemeMode';
import { Palette, ChartPalette, paletteFor, chartPalette, ThemeController } from '../common/Palette';
import { LockerStore } from '../data/LockerStore';
import { LockerDao } from '../data/LockerDao';
import { LockerRepository } from '../data/LockerRepository';
import { AppState } from '../ui/AppState';
import { HomePage } from '../ui/HomePage';
import { StatsPage } from '../ui/StatsPage';
import { CheckInPage, FullScreenNumber } from '../ui/CheckInPage';
import { SettingsPage } from '../ui/SettingsPage';
import { GuidePage } from '../ui/GuidePage';
import { SendSyncDialog, ReceiveSyncDialog } from '../ui/SyncDialogs';
import { SegmentEditDialog, EventEditDialog } from '../ui/components/EditDialogs';
import { EventRecord, WearSegment } from '../data/Models';
import { PendingOpenFile } from '../common/PendingOpenFile';

/**
 * 根页面：底部四个页签（主页 / 统计 / 打卡 / 设置）。
 *
 * 状态只在这里创建一次，各页签通过 @ObjectLink 拿到同一个 AppState；
 * AppState 用 @Observed + @Trace 标记，字段变化会驱动订阅它的组件刷新。
 * 所有覆盖层弹窗都挂在根 Stack 上，避免各页签各写一套。
 */
@Entry
@Component
struct Index {
  @State private ready: boolean = false;
  @State private currentTab: number = 0;
  @State private pressedTab: number = -1;
  @State private tabBarWidth: number = 0;
  @State private swiperWidth: number = 0;
  @State private indicatorPagePosition: number = 0;
  @State private touchGlowX: number = 0;
  @State private touchGlowY: number = 32;
  @State private touchGlowOpacity: number = 0;
  @State private touchGlowScale: number = 0.75;
  @State private tabBarPressed: boolean = false;
  @State private reduceMotion: boolean = false;
  @State private fullScreenNumber: string = '';
  @State private fullScreenCloseRequested: boolean = false;
  @State private palette: Palette = paletteFor(false);
  /** 首次加载不播主题切换动画，避免冷启动时整屏颜色渐变。 */
  private paletteLoaded: boolean = false;
  @State private chart: ChartPalette = chartPalette(false);
  @State private appState: AppState | null = null;
  @State private sendDialogVisible: boolean = false;
  @State private receiveDialogVisible: boolean = false;
  @State private receiveZipPath: string = '';
  /** 使用指南整页：覆盖在页签之上，从设置页「关于」进入。 */
  @State private showGuide: boolean = false;
  @State private editingSegment: WearSegment | null = null;
  @State private addingSegment: boolean = false;
  @State private editingEvent: EventRecord | null = null;
  @State private statsRefreshToken: number = 0;
  @State private undoVisible: boolean = false;
  @State private undoTypeName: string = '';
  @State private undoEventId: number = -1;
  @State private undoToken: number = 0;
  /** 页签滑动控制器：底部按钮和手势滑动都走它，保证动画一致。 */
  private swiperController: SwiperController = new SwiperController();
  private skipClickTab: number = -1;
  private tabTouchStartX: number = 0;
  private tabDragging: boolean = false;
  private lastMessageSeq: number = 0;
  private messageTimer: number = -1;
  /** 轮询外部应用交进来的同步包（Ability 先于页面收到 want.uri）。 */
  private openFileTimer: number = -1;

  aboutToAppear(): void {
    this.init();
  }

  aboutToDisappear(): void {
    if (this.messageTimer >= 0) {
      clearInterval(this.messageTimer);
      this.messageTimer = -1;
    }
    this.stopOpenFileWatch();
  }

  onPageShow(): void {
    this.refreshMotionPreference();
    this.applyColorMode();
    // 应用可能已在后台，Ability 的 onNewWant 随时可能写入待导入文件
    this.consumePendingOpenFile();
    this.startOpenFileWatch();
  }

  onPageHide(): void {
    this.stopOpenFileWatch();
  }

  onBackPress(): boolean {
    if (this.fullScreenNumber.length > 0) {
      this.fullScreenCloseRequested = true;
      return true;
    }
    return false;
  }

  private tabAmount(index: number): number {
    return Math.max(0, 1 - Math.abs(this.indicatorPagePosition - index));
  }

  private refreshMotionPreference(): void {
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const durationScale = Number(settings.getValueSync(
        context,
        settings.display.ANIMATOR_DURATION_SCALE,
        '1',
      ));
      this.reduceMotion = Number.isFinite(durationScale) && durationScale === 0;
    } catch (err) {
      this.reduceMotion = false;
    }
  }

  private handleTabTouch(index: number, event: TouchEvent): void {
    const point = event.changedTouches.length > 0 ? event.changedTouches[0] : event.touches[0];
    if (point === undefined || this.tabBarWidth <= 0) {
      return;
    }
    const slotWidth = this.tabBarWidth / 4;
    const x = Math.min(this.tabBarWidth, Math.max(0, index * slotWidth + point.x));
    this.touchGlowX = x;
    this.touchGlowY = Math.min(64, Math.max(0, point.y));

    if (event.type === TouchType.Down) {
      this.skipClickTab = -1;
      this.tabTouchStartX = x;
      this.tabDragging = false;
      this.pressedTab = index;
      this.touchGlowScale = 0.75;
      if (this.reduceMotion) {
        this.touchGlowOpacity = 0.65;
      } else {
        this.getUIContext().animateTo({ duration: 90, curve: Curve.EaseOut }, () => {
          this.touchGlowOpacity = 1;
          this.touchGlowScale = 1;
          this.tabBarPressed = true;
        });
      }
    } else if (event.type === TouchType.Move) {
      this.pressedTab = Math.min(3, Math.floor(x / slotWidth));
      if (Math.abs(x - this.tabTouchStartX) > 6) {
        this.tabDragging = true;
      }
      if (this.tabDragging) {
        this.indicatorPagePosition = Math.min(3, Math.max(0, x / slotWidth - 0.5));
      }
    } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
      if (event.type === TouchType.Up) {
        const target = this.tabDragging
          ? Math.round(Math.min(3, Math.max(0, x / slotWidth - 0.5)))
          : Math.min(3, Math.floor(x / slotWidth));
        if (this.tabDragging || target !== index) {
          this.skipClickTab = index;
          if (target === this.currentTab) {
            if (this.reduceMotion) {
              this.indicatorPagePosition = target;
            } else {
              this.getUIContext().animateTo({ duration: 160, curve: Curve.EaseOut }, () => {
                this.indicatorPagePosition = target;
              });
            }
          } else {
            this.selectTab(target);
          }
        }
      } else if (this.tabDragging) {
        this.indicatorPagePosition = this.currentTab;
      }
      this.tabDragging = false;
      if (this.reduceMotion) {
        this.pressedTab = -1;
        this.touchGlowOpacity = 0;
        this.tabBarPressed = false;
      } else {
        this.getUIContext().animateTo({ duration: 220, curve: curves.springMotion(0.25, 0.78) }, () => {
          this.pressedTab = -1;
          this.tabBarPressed = false;
          this.touchGlowScale = 1.18;
          this.touchGlowOpacity = 0;
        });
      }
    }
  }

  private selectTab(index: number): void {
    if (this.currentTab === index) {
      return;
    }
    this.swiperController.changeIndex(index, true);
  }

  /** 每 500ms 看一眼有没有外部打开的文件，取到就清空。 */
  private startOpenFileWatch(): void {
    if (this.openFileTimer >= 0) {
      return;
    }
    this.openFileTimer = setInterval(() => {
      this.consumePendingOpenFile();
    }, 500);
  }

  private stopOpenFileWatch(): void {
    if (this.openFileTimer >= 0) {
      clearInterval(this.openFileTimer);
      this.openFileTimer = -1;
    }
  }

  /**
   * 有待导入的同步包时切到设置页并打开发送/接收弹窗里的「接收」，
   * 与 Android 版 ACTION_VIEW / ACTION_SEND 的处理一致。
   */
  private consumePendingOpenFile(): void {
    // 快捷方式动作优先：切到打卡页，由页面自己处理发送流程
    const action = PendingOpenFile.takeAction();
    if (action.length > 0) {
      if (!this.ready || this.appState === null) {
        PendingOpenFile.putAction(action);
        return;
      }
      if (this.currentTab !== 2) {
        this.swiperController.changeIndex(2, true);
      }
      if (action === 'sendToday') {
        this.sendDialogVisible = true;
      }
    }

    const uri = PendingOpenFile.take();
    if (uri.length === 0) {
      return;
    }
    // 页面还没就绪时先放回去，等 init 完成后再取
    if (!this.ready || this.appState === null) {
      PendingOpenFile.put(uri);
      return;
    }
    if (this.currentTab !== 3) {
      this.swiperController.changeIndex(3, true);
    }
    this.receiveZipPath = uri;
    this.receiveDialogVisible = true;
  }

  onConfigurationUpdate(config: Configuration): void {
    this.applyColorMode();
  }

  private async init(): Promise<void> {
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const store = await LockerStore.init(context);
      const repo = new LockerRepository(new LockerDao(store));
      const state = new AppState(repo);
      // 注入 context，让 AppState 能在数据变更后刷新桌面卡片
      state.setContext(context);
      await state.load();
      this.appState = state;
      this.ready = true;
      this.applyColorMode();
      this.watchMessages();
    } catch (err) {
      promptAction.showToast({ message: (err as Error).message ?? '初始化失败' });
    }
  }

  /** 浅色 / 深色：同时更新系统 colorMode 与本地调色板。 */
  private applyColorMode(): void {
    const context = getContext(this) as common.UIAbilityContext;
    const systemDark =
      context.config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
    const dark = ThemeController.isDark(systemDark);
    const next = paletteFor(dark);
    const nextChart = chartPalette(dark);
    if (this.paletteLoaded && next.dark !== this.palette.dark) {
      // 深浅色切换时让颜色滑过去，而不是瞬间跳变（与 Android 版 450ms 对齐）
      animateTo({ duration: 450, curve: Curve.EaseInOut }, () => {
        this.palette = next;
        this.chart = nextChart;
      });
    } else {
      this.palette = next;
      this.chart = nextChart;
      this.paletteLoaded = true;
    }
  }

  /** AppState 的一次性提示转成轻提示条。 */
  private watchMessages(): void {
    this.messageTimer = setInterval(() => {
      const state = this.appState;
      if (state === null) {
        return;
      }
      if (state.messageSeq !== this.lastMessageSeq) {
        this.lastMessageSeq = state.messageSeq;
        if (state.message.length > 0) {
          promptAction.showToast({ message: state.message, duration: 2500 });
        }
      }
    }, 120);
  }

  private notifyEventRecorded(typeName: string, eventId: number): void {
    this.undoTypeName = typeName;
    this.undoEventId = eventId;
    this.undoVisible = true;
    this.undoToken++;
    const token = this.undoToken;
    setTimeout(() => {
      if (token === this.undoToken) {
        this.undoVisible = false;
      }
    }, 3000);
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        Stack() {
          if (!this.ready || this.appState === null) {
            Column({ space: 12 }) {
              LoadingProgress()
                .width(40)
                .height(40)
                .color(this.palette.primary)
              Text('正在加载…')
                .fontSize(14)
                .fontColor(this.palette.onSurfaceVariant)
            }
            .width('100%')
            .height('100%')
            .justifyContent(FlexAlign.Center)
          } else {
            Swiper(this.swiperController) {
              HomePage({
                palette: this.palette,
                dark: this.palette.dark,
                reduceMotion: this.reduceMotion,
                visible: this.currentTab === 0,
                state: this.appState,
                onEventRecorded: (typeName: string, eventId: number) => {
                  this.notifyEventRecorded(typeName, eventId);
                },
              })

              StatsPage({
                palette: this.palette,
                chart: this.chart,
                visible: this.currentTab === 1,
                reduceMotion: this.reduceMotion,
                refreshToken: this.statsRefreshToken,
                state: this.appState,
                onEditSegment: (segment: WearSegment) => {
                  this.editingSegment = segment;
                },
                onAddSegment: () => {
                  this.addingSegment = true;
                },
                onEditEvent: (event: EventRecord) => {
                  this.editingEvent = event;
                },
                onDeleteSegment: (segment: WearSegment) => {
                  this.appState!.deleteSegment(segment).then(() => {
                    this.statsRefreshToken++;
                  });
                },
                onDeleteEvent: (event: EventRecord) => {
                  this.appState!.deleteEvent(event.id).then(() => {
                    this.statsRefreshToken++;
                  });
                },
              })

              CheckInPage({
                palette: this.palette,
                state: this.appState,
                visible: this.currentTab === 2,
                onOpenSendDialog: () => {
                  this.sendDialogVisible = true;
                },
                onOpenFullScreenNumber: (number: string) => {
                  this.fullScreenCloseRequested = false;
                  this.fullScreenNumber = number;
                },
              })

              SettingsPage({
                palette: this.palette,
                state: this.appState,
                onThemeModeChange: (_mode: ThemeMode) => {
                  this.applyColorMode();
                },
                onOpenSendDialog: () => {
                  this.sendDialogVisible = true;
                },
                onOpenReceiveDialog: () => {
                  this.receiveZipPath = '';
                  this.receiveDialogVisible = true;
                },
                onOpenGuide: () => {
                  this.showGuide = true;
                },
              })
            }
            .index(this.currentTab)
            .indicator(false)
            .loop(false)
            .cachedCount(1)
            .disableSwipe(this.fullScreenNumber.length > 0)
            .duration(this.reduceMotion ? 0 : 180)
            .curve(Curve.EaseOut)
            .width('100%')
            .height('100%')
            .onAreaChange((oldValue: Area, newValue: Area) => {
              this.swiperWidth = newValue.width as number;
            })
            .onChange((index: number) => {
              this.currentTab = index;
            })
            .onAnimationStart((_index: number, targetIndex: number) => {
              if (this.reduceMotion) {
                this.indicatorPagePosition = targetIndex;
              } else {
                this.getUIContext().animateTo({
                  duration: 180,
                  curve: Curve.EaseOut,
                }, () => {
                  this.indicatorPagePosition = targetIndex;
                });
              }
            })
            .onAnimationEnd((index: number) => {
              this.indicatorPagePosition = index;
            })
            .onGestureSwipe((index: number, extraInfo: SwiperAnimationEvent) => {
              if (this.swiperWidth > 0) {
                const pagePosition = index - extraInfo.currentOffset / this.swiperWidth;
                this.indicatorPagePosition = Math.min(3, Math.max(0, pagePosition));
              }
            })
          }
        }
        .height('100%')
        .width('100%')
      }
      .width('100%')
      .height('100%')
      .backgroundColor(this.palette.background)

      if (this.fullScreenNumber.length === 0) {
        this.TabBar()
      }

      // 记录事件后的撤销提示条
      // 使用底部对齐定位，避免固定百分比导致不同屏幕位置不一致
      if (this.undoVisible) {
        Row({ space: 12 }) {
          Text(`已记录 · ${this.undoTypeName}`)
            .fontSize(14)
            .fontColor(this.palette.onSurface)
            .layoutWeight(1)
          Text('撤销')
            .fontSize(14)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.palette.primary)
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => {
              this.undoVisible = false;
              if (this.undoEventId > 0) {
                this.appState?.deleteEvent(this.undoEventId);
                this.undoEventId = -1;
              }
            })
        }
        .width('90%')
        .padding({ left: 16, right: 16, top: 12, bottom: 12 })
        .borderRadius(20)
        .backgroundColor(this.palette.surface)
        .shadow({ radius: 16, color: '#33000000', offsetY: 4 })
        .margin({ bottom: 104 })
        .transition(TransitionEffect.asymmetric(
          TransitionEffect.OPACITY
            .combine(TransitionEffect.translate({ y: 16 }))
            .animation({ duration: 220, curve: Curve.EaseOut }),
          TransitionEffect.OPACITY
            .combine(TransitionEffect.translate({ y: 8 }))
            .animation({ duration: 160, curve: Curve.EaseIn }),
        ))
      }

      if (this.showGuide) {
        GuidePage({
          palette: this.palette,
          onBack: () => {
            this.showGuide = false;
          },
        })
        // 进场从右侧滑入，退场向右滑出
        .transition(TransitionEffect.asymmetric(
          TransitionEffect.OPACITY
            .combine(TransitionEffect.translate({ x: 40 }))
            .animation({ duration: 260, curve: Curve.EaseOut }),
          TransitionEffect.OPACITY
            .combine(TransitionEffect.translate({ x: 40 }))
            .animation({ duration: 200, curve: Curve.EaseIn }),
        ))
      }

      if (this.sendDialogVisible && this.appState !== null) {
        SendSyncDialog({
          palette: this.palette,
          state: this.appState,
          onDismiss: () => {
            this.sendDialogVisible = false;
          },
        })
      }

      if (this.receiveDialogVisible && this.appState !== null) {
        ReceiveSyncDialog({
          palette: this.palette,
          state: this.appState,
          initialZipPath: this.receiveZipPath,
          onDismiss: () => {
            this.receiveDialogVisible = false;
          },
        })
      }

      if (this.addingSegment && this.appState !== null) {
        SegmentEditDialog({
          palette: this.palette,
          initial: null,
          onDismiss: () => {
            this.addingSegment = false;
          },
          onSave: (startAt: number, endAt: number | null) => {
            this.addingSegment = false;
            this.appState!.addManualSegment(startAt, endAt).then(() => {
              this.statsRefreshToken++;
            });
          },
          onDelete: null,
        })
      }

      if (this.editingSegment !== null && this.appState !== null) {
        SegmentEditDialog({
          palette: this.palette,
          initial: this.editingSegment!,
          onDismiss: () => {
            this.editingSegment = null;
          },
          onSave: (startAt: number, endAt: number | null) => {
            const segment = this.editingSegment!;
            this.editingSegment = null;
            this.appState!.updateSegment(segment.id, segment.personId, startAt, endAt).then(() => {
              this.statsRefreshToken++;
            });
          },
          onDelete: () => {
            const segment = this.editingSegment!;
            this.editingSegment = null;
            this.appState!.deleteSegment(segment).then(() => {
              this.statsRefreshToken++;
            });
          },
        })
      }

      if (this.editingEvent !== null && this.appState !== null) {
        EventEditDialog({
          palette: this.palette,
          initial: this.editingEvent!,
          eventTypes: this.appState!.eventTypes,
          onDismiss: () => {
            this.editingEvent = null;
          },
          onSave: (typeKey: string, occurredAt: number, note: string | null) => {
            const event = this.editingEvent!;
            this.editingEvent = null;
            this.appState!.updateEvent(event.id, event.personId, typeKey, occurredAt, note).then(() => {
              this.statsRefreshToken++;
            });
          },
          onDelete: () => {
            const event = this.editingEvent!;
            this.editingEvent = null;
            this.appState!.deleteEvent(event.id).then(() => {
              this.statsRefreshToken++;
            });
          },
        })
      }

      if (this.fullScreenNumber.length > 0) {
        FullScreenNumber({
          number: this.fullScreenNumber,
          closeRequested: this.fullScreenCloseRequested,
          onDismiss: () => {
            this.fullScreenCloseRequested = false;
            this.fullScreenNumber = '';
          },
        })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette.background)
  }

  @Builder
  TabBar() {
    Row() {
      Stack({ alignContent: Alignment.TopStart }) {
        Row()
          .width(112)
          .height(112)
          .radialGradient({
            center: ['50%', '50%'],
            radius: '50%',
            colors: this.palette.dark
              ? [['#D6FFFFFF', 0], ['#72FFFFFF', 0.42], ['#00FFFFFF', 1]]
              : [['#E8FFFFFF', 0], ['#8CFFFFFF', 0.42], ['#00FFFFFF', 1]],
          })
          .position({ x: this.touchGlowX - 56, y: this.touchGlowY - 56 })
          .opacity(this.touchGlowOpacity)
          .scale({ x: this.touchGlowScale, y: this.touchGlowScale })

        Row() {
          this.TabItem(0, '主页')
          this.TabItem(1, '统计')
          this.TabItem(2, '打卡')
          this.TabItem(3, '设置')
        }
        .width('100%')
        .height(64)
      }
      .width('100%')
      .height(64)
      .constraintSize({ maxWidth: 520 })
      .onAreaChange((oldValue: Area, newValue: Area) => {
        this.tabBarWidth = newValue.width as number;
      })
      .borderRadius(32)
      .backgroundEffect({
        radius: 22,
        saturation: 1,
        color: this.palette.dark ? '#A6262626' : '#B8FFFFFF',
      })
      .borderWidth(1)
      .borderColor(this.palette.dark ? '#55FFFFFF' : '#E6FFFFFF')
      .clip(true)
      .shadow({ radius: 20, color: this.palette.dark ? '#58000000' : '#26000000', offsetY: 6 })
      .scale({ x: this.tabBarPressed ? 0.995 : 1, y: this.tabBarPressed ? 0.965 : 1 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 8, bottom: 12 })
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  TabItem(index: number, label: string) {
    Column({ space: 2 }) {
      Stack({ alignContent: Alignment.Center }) {
        Path({ width: 24, height: 24, commands: this.pathToPixels(this.tabIconPath(index)) })
          .fillOpacity(0)
          .stroke(this.palette.onSurfaceVariant)
          .strokeWidth(1.8)
          .strokeLineCap(LineCapStyle.Round)
          .strokeLineJoin(LineJoinStyle.Round)
        Path({ width: 24, height: 24, commands: this.pathToPixels(this.tabIconPath(index)) })
          .fillOpacity(0)
          .stroke(this.palette.primary)
          .strokeWidth(1.8)
          .strokeLineCap(LineCapStyle.Round)
          .strokeLineJoin(LineJoinStyle.Round)
          .opacity(this.tabAmount(index))
      }
      .width(56)
      .height(28)
      .scale({
        x: this.pressedTab === index ? 0.94 : 1,
        y: this.pressedTab === index ? 0.94 : 1,
      })
      Stack({ alignContent: Alignment.Center }) {
        Text(label)
          .fontSize(11)
          .fontColor(this.palette.onSurfaceVariant)
        Text(label)
          .fontSize(11)
          .fontColor(this.palette.primary)
          .opacity(this.tabAmount(index))
          .accessibilityLevel('no')
      }
      .width('100%')
      .height(16)
    }
    .layoutWeight(1)
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .onTouch((event: TouchEvent) => {
      this.handleTabTouch(index, event);
    })
    .onClick(() => {
      if (this.skipClickTab === index) {
        this.skipClickTab = -1;
        return;
      }
      if (this.currentTab !== index) {
        this.selectTab(index);
      }
    })
  }

  private tabIconPath(index: number): string {
    if (index === 0) {
      return 'M3 10.5 L12 3 L21 10.5 M5.5 9.5 V20 H10 V14 H14 V20 H18.5 V9.5';
    }
    if (index === 1) {
      return 'M4 19 V12 H8 V19 Z M10 19 V5 H14 V19 Z M16 19 V9 H20 V19 Z';
    }
    if (index === 2) {
      return 'M6 3 V7 M18 3 V7 M4 5 H20 V21 H4 Z M7 11 H10 M14 11 H17 M7 15 H10 M14 15 H17';
    }
    return 'M8.86 4.42 L9.90 2.12 L14.10 2.12 L15.14 4.42 L17.50 3.53 L20.47 6.50 L19.58 8.86 L21.88 9.90 L21.88 14.10 L19.58 15.14 L20.47 17.50 L17.50 20.47 L15.14 19.58 L14.10 21.88 L9.90 21.88 L8.86 19.58 L6.50 20.47 L3.53 17.50 L4.42 15.14 L2.12 14.10 L2.12 9.90 L4.42 8.86 L3.53 6.50 L6.50 3.53 Z M12 9 C13.66 9 15 10.34 15 12 C15 13.66 13.66 15 12 15 C10.34 15 9 13.66 9 12 C9 10.34 10.34 9 12 9 Z';
  }

  private pathToPixels(path: string): string {
    const density = vp2px(1);
    return path.replace(/-?\d+(\.\d+)?/g, (value: string): string => `${Number(value) * density}`);
  }
}
