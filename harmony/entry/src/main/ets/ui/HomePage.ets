import { curves } from '@kit.ArkUI';
import { tapFeedback } from '../common/Haptics';
import { Palette } from '../common/Palette';
import { AppState } from './AppState';
import { EventTypeEntity, Person, WearSegment } from '../data/Models';
import { formatDurationWithSeconds, formatTime } from '../common/TimeFormat';
import { EventTypeButtons, PersonSelector } from './components/Selectors';
import { ConfirmDialog, FormDialog } from './components/Dialogs';
import { DateTimeField } from './components/EditDialogs';
import { colorString } from '../common/EventColors';

/**
 * 主页：当前人物的佩戴状态、上锁/开锁、事件记录。
 *
 * 与 Android 版一致：
 * - 状态卡在佩戴中整卡转为 primaryContainer，并显示本次已持续的秒级时长；
 * - 上锁/开锁都要二次确认，避免误触；
 * - 点事件类型打开编辑框，长按立即记录并给出「撤销」。
 */
@Component
export struct HomePage {
  @Prop palette: Palette;
  @Prop dark: boolean;
  @Prop reduceMotion: boolean = false;
  @Prop @Watch('onVisibleChanged') visible: boolean = false;
  @ObjectLink state: AppState;
  onEventRecorded: (typeName: string, eventId: number) => void = () => {
  };

  @State private now: number = Date.now();
  @State private pendingWearAction: string = '';
  @State private pendingTypeKey: string = '';
  @State private showEventDialog: boolean = false;
  @State private timerId: number = -1;
  @State private personSwitching: boolean = false;
  @State private pendingPerson: Person | null = null;
  /** 上锁/开锁按钮的按压态，用于缩放反馈。 */
  @State private buttonPressed: boolean = false;
  @State private statusCardScale: number = 1;
  private statusBounceToken: number = 0;
  /** 人物切换时状态卡的横向位移与透明度，用于滑入淡入。 */
  @State private personShift: number = 0;
  @State private personAlpha: number = 1;

  aboutToAppear(): void {
    if (this.visible) {
      this.startTimer();
    }
  }

  aboutToDisappear(): void {
    this.stopTimer();
    this.statusBounceToken++;
  }

  private startTimer(): void {
    if (this.timerId >= 0) {
      return;
    }
    this.now = Date.now();
    this.timerId = setInterval(() => {
      this.now = Date.now();
    }, 1000);
  }

  onVisibleChanged(): void {
    if (this.visible) {
      this.startTimer();
    } else {
      this.stopTimer();
    }
  }

  /**
   * 切换人物：旧内容向左/右滑出并淡出，新内容从反方向滑入并淡入。
   * 方向按人物在列表里的前后顺序决定，与 Android 版的 AnimatedContent 一致。
   * 使用 onFinish 回调替代嵌套 setTimeout，避免动画时序脆弱。
   */
  private switchPerson(person: Person): void {
    if (this.personSwitching) {
      this.pendingPerson = person;
      return;
    }
    if (this.state.currentPerson !== null && this.state.currentPerson!.id === person.id) {
      return;
    }
    this.personSwitching = true;
    const persons = this.state.persons;
    let oldIndex = persons.length;
    let newIndex = 0;
    for (let i = 0; i < persons.length; i++) {
      if (this.state.currentPerson !== null && persons[i].id === this.state.currentPerson!.id) {
        oldIndex = i;
      }
      if (persons[i].id === person.id) {
        newIndex = i;
      }
    }
    const direction = newIndex >= oldIndex ? 1 : -1;

    if (this.reduceMotion) {
      this.state.selectPerson(person).then(() => {
        this.finishPersonSwitch();
      }).catch(() => {
        this.finishPersonSwitch();
      });
      return;
    }

    // 对齐 Android AnimatedContent：旧内容滑出 200ms，位移约为卡片宽度的 1/8。
    // 使用 onFinish 回调确保动画完成后再执行数据切换
    animateTo({
      duration: 200,
      curve: Curve.EaseIn,
      onFinish: () => {
        this.state.selectPerson(person).then(() => {
          // 等新人物状态完成布局后，从反方向淡入。
          this.personShift = 44 * direction;
          animateTo({
            duration: 260,
            curve: Curve.FastOutSlowIn,
            onFinish: () => {
              this.finishPersonSwitch();
            }
          }, () => {
            this.personAlpha = 1;
            this.personShift = 0;
          });
        }).catch(() => {
          this.finishPersonSwitch();
        });
      }
    }, () => {
      this.personAlpha = 0;
      this.personShift = -44 * direction;
    });
  }

  private finishPersonSwitch(): void {
    this.personAlpha = 1;
    this.personShift = 0;
    this.personSwitching = false;
    const nextPerson = this.pendingPerson;
    this.pendingPerson = null;
    if (nextPerson !== null && this.state.currentPerson?.id !== nextPerson.id) {
      this.switchPerson(nextPerson);
    }
  }

  private stopTimer(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  private isWearing(): boolean {
    const person = this.state.currentPerson;
    const segment = this.state.activeSegment;
    return person !== null && segment !== null && segment.personId === person.id;
  }

  private bounceStatusCard(): void {
    if (this.reduceMotion) {
      this.statusCardScale = 1;
      return;
    }
    const token = ++this.statusBounceToken;
    this.statusCardScale = 0.98;
    this.getUIContext().animateTo({
      duration: 150,
      curve: Curve.EaseOut,
      onFinish: () => {
        if (token !== this.statusBounceToken) {
          return;
        }
        this.getUIContext().animateTo({
          duration: 280,
          curve: curves.springMotion(0.3, 0.65),
        }, () => {
          this.statusCardScale = 1;
        });
      },
    }, () => {
      this.statusCardScale = 1.035;
    });
  }

  private activeSegmentForCurrentPerson(): WearSegment | null {
    const person = this.state.currentPerson;
    const segment = this.state.activeSegment;
    if (person === null || segment === null || segment.personId !== person.id) {
      return null;
    }
    return segment;
  }

  private cardColor(): string {
    return this.isWearing() ? this.palette.primaryContainer : this.palette.surface;
  }

  private statusColor(): string {
    return this.isWearing() ? this.palette.onPrimaryContainer : this.palette.onSurface;
  }

  private elapsed(): number {
    const segment = this.state.activeSegment;
    if (segment === null) {
      return 0;
    }
    return Math.max(0, this.now - segment.startAt);
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Scroll() {
        Column({ space: 0 }) {
          Text('Locker')
            .fontSize(28)
            .fontFamily('HarmonyOS Sans')
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onSurface)
            .width('100%')
            .margin({ bottom: 12 })

          PersonSelector({
            palette: this.palette,
            persons: this.state.persons,
            selectedPersonId: this.state.currentPerson === null ? -1 : this.state.currentPerson!.id,
            onSelect: (person: Person) => {
              this.switchPerson(person);
            },
          })
            .margin({ bottom: 20 })

          if (this.state.currentPerson === null) {
            Column() {
              Text('先新建一个人物，才能开始计时。')
                .fontSize(14)
                .fontColor(this.palette.onSurfaceVariant)
            }
            .width('100%')
            .padding(20)
            .backgroundColor(this.palette.surface)
            .borderRadius(20)
          } else {
            Column() {
              this.StatusCard()
            }
            .width('100%')
            .translate({ x: this.personShift })
            .opacity(this.personAlpha)

            Button() {
              if (this.isWearing()) {
                Text('开锁')
                  .fontSize(17)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(this.palette.onSecondary)
                  .transition(TransitionEffect.asymmetric(
                    TransitionEffect.OPACITY.animation({ duration: 200, curve: Curve.FastOutSlowIn }),
                    TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseIn }),
                  ))
              } else {
                Text('上锁')
                  .fontSize(17)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(this.palette.onPrimary)
                  .transition(TransitionEffect.asymmetric(
                    TransitionEffect.OPACITY.animation({ duration: 200, curve: Curve.FastOutSlowIn }),
                    TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseIn }),
                  ))
              }
            }
              .width('100%')
              .height(56)
              .backgroundColor(this.isWearing() ? this.palette.secondary : this.palette.primary)
              .borderRadius(28)
              .margin({ top: 20, bottom: 32 })
              .scale({ x: this.buttonPressed ? 0.96 : 1, y: this.buttonPressed ? 0.96 : 1 })
              .onTouch((event: TouchEvent) => {
                if (event.type === TouchType.Down) {
                  if (this.reduceMotion) {
                    this.buttonPressed = false;
                  } else {
                    animateTo({ duration: 120, curve: Curve.EaseOut }, () => {
                      this.buttonPressed = true;
                    });
                  }
                } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
                  if (this.reduceMotion) {
                    this.buttonPressed = false;
                  } else {
                    animateTo({ duration: 120, curve: Curve.EaseOut }, () => {
                      this.buttonPressed = false;
                    });
                  }
                }
              })
              .onClick(() => {
                this.pendingWearAction = this.isWearing() ? 'UNLOCK' : 'LOCK';
              })

            Row() {
              Text('记录事件')
                .fontSize(18)
                .fontWeight(FontWeight.Bold)
                .fontColor(this.palette.onSurface)
              Blank()
              Text('长按可快速记录')
                .fontSize(12)
                .fontColor(this.palette.onSurfaceVariant)
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
            .margin({ bottom: 12 })

            if (this.state.eventTypes.length === 0) {
              Text('请先在设置中创建事件类型')
                .fontSize(14)
                .fontColor(this.palette.onSurfaceVariant)
                .width('100%')
            } else {
              EventTypeButtons({
                palette: this.palette,
                eventTypes: this.state.eventTypes,
                onClickType: (type: EventTypeEntity) => {
                  this.pendingTypeKey = type.typeKey;
                  this.showEventDialog = true;
                },
                onLongPressType: (type: EventTypeEntity) => {
                  this.state.addEvent(type.typeKey, Date.now(), null)
                    .then((eventId: number | null) => {
                      if (eventId !== null) {
                        this.onEventRecorded(type.name, eventId);
                      }
                    });
                },
              })
            }
          }
        }
        .width('100%')
        .padding({ left: 24, right: 24, top: 16, bottom: 120 })
        .alignItems(HorizontalAlign.Center)
      }
      .scrollBar(BarState.Off)
      .width('100%')
      .height('100%')
      .backgroundColor(this.palette.background)
      .align(Alignment.Top)

      if (this.pendingWearAction.length > 0) {
        ConfirmDialog({
          palette: this.palette,
          reduceMotion: this.reduceMotion,
          title: this.pendingWearAction === 'LOCK' ? '确认上锁？' : '确认开锁？',
          message: this.pendingWearAction === 'LOCK'
            ? `将为「${this.state.currentPerson!.name}」开始计时。`
            : `将结束「${this.state.currentPerson!.name}」的本次计时。`,
          confirmText: this.pendingWearAction === 'LOCK' ? '上锁' : '开锁',
          icon: '',
          iconKind: this.pendingWearAction === 'LOCK' ? 'lock' : 'unlock',
          tone: this.pendingWearAction === 'LOCK' ? 'primary' : 'neutral',
          onConfirm: () => {
            if (this.pendingWearAction === 'LOCK') {
              const personId = this.state.currentPerson!.id;
              this.state.startWearing().then(() => {
                if (this.state.currentPerson?.id === personId && this.isWearing()) {
                  this.bounceStatusCard();
                }
              });
            } else {
              this.state.stopWearing();
            }
            this.pendingWearAction = '';
          },
          onDismiss: () => {
            this.pendingWearAction = '';
          },
        })
      }

      if (this.showEventDialog) {
        HomeEventEditDialog({
          palette: this.palette,
          eventTypes: this.state.eventTypes,
          initialTypeKey: this.pendingTypeKey,
          onDismiss: () => {
            this.showEventDialog = false;
          },
          onSave: (typeKey: string, occurredAt: number, note: string | null) => {
            const typeName = this.state.eventTypes
              .find((t: EventTypeEntity) => t.typeKey === typeKey)?.name ?? '事件';
            this.state.addEvent(typeKey, occurredAt, note)
              .then((eventId: number | null) => {
                if (eventId !== null) {
                  this.onEventRecorded(typeName, eventId);
                }
              });
            this.showEventDialog = false;
          },
        })
      }
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  StatusCard() {
    Column() {
      Text(`当前状态 · ${this.state.currentPerson!.name}`)
        .fontSize(12)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .fontColor(this.isWearing() ? this.palette.onPrimaryContainer : this.palette.onSurfaceVariant)

      Row({ space: 8 }) {
        if (this.visible && this.isWearing()) {
          // 保持固定图标槽位，避免状态切换时文字水平跳动。
          PulsingDot({ color: this.palette.primary, reduceMotion: this.reduceMotion })
        } else {
          Blank()
            .width(10)
            .height(10)
        }
        // 状态文字：退出比进入更快，避免旧状态在卡片变色时滞留。
        if (this.isWearing()) {
          Text('佩戴中')
            .fontSize(28)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.statusColor())
            .transition(TransitionEffect.asymmetric(
              TransitionEffect.OPACITY.animation({ duration: 220, curve: Curve.FastOutSlowIn }),
              TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseIn }),
            ))
        } else {
          Text('未佩戴')
            .fontSize(28)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.statusColor())
            .transition(TransitionEffect.asymmetric(
              TransitionEffect.OPACITY.animation({ duration: 220, curve: Curve.FastOutSlowIn }),
              TransitionEffect.OPACITY.animation({ duration: 120, curve: Curve.EaseIn }),
            ))
        }
      }
      .alignItems(VerticalAlign.Center)
      .height(40)
      .margin({ top: 6 })

      if (this.isWearing()) {
        Column() {
          Text(formatDurationWithSeconds(this.elapsed()))
            .fontSize(38)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.statusColor())
            .fontFamily('HarmonyOS Sans Mono')
          Text(`本次已持续 · ${formatTime(this.activeSegmentForCurrentPerson()!.startAt)} 开始`)
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .margin({ top: 2 })
        }
        .height(60)
        .margin({ top: 16 })
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 6 }))
          .animation({ duration: 240, curve: Curve.EaseOut }))
      }
    }
    .width('100%')
    .height(this.isWearing() ? 190 : 114)
    .padding({ top: 28, bottom: 28, left: 20, right: 20 })
    .backgroundColor(this.cardColor())
    .borderRadius(20)
    .clip(true)
    .animation({ duration: this.reduceMotion ? 0 : 300, curve: Curve.FastOutSlowIn })
    .scale({ x: this.statusCardScale, y: this.statusCardScale })
    .alignItems(HorizontalAlign.Center)
  }
}

/** 佩戴中的呼吸圆点。 */
@Component
struct PulsingDot {
  @Prop color: string;
  @Prop reduceMotion: boolean = false;
  @State private alpha: number = 1;

  build() {
    Row()
      .width(10)
      .height(10)
      .borderRadius(5)
      .backgroundColor(this.color)
      .opacity(this.alpha)
      .onAppear(() => {
        if (this.reduceMotion) {
          this.alpha = 1;
          return;
        }
        // 一条无限往返动画代替定时器：没有定时器唤醒，也不会在方向反转处顿一下
        animateTo({
          duration: 900,
          curve: Curve.EaseInOut,
          iterations: -1,
          playMode: PlayMode.Alternate,
        }, () => {
          this.alpha = 0.25;
        });
      })
  }
}

/**
 * 主页的事件编辑弹窗：类型选择 + 发生时间 + 备注。
 * 与 Android 版的 EventEditDialog 对应；统计页的编辑框带删除，在 EditDialogs 里单独实现。
 */
@Component
export struct HomeEventEditDialog {
  @Prop palette: Palette;
  @Prop eventTypes: EventTypeEntity[] = [];
  initialTypeKey: string = '';
  onDismiss: () => void = () => {
  };
  onSave: (typeKey: string, occurredAt: number, note: string | null) => void = () => {
  };

  @State private typeKey: string = '';
  @State private occurredAt: number = Date.now();
  @State private note: string = '';

  aboutToAppear(): void {
    this.typeKey = this.initialTypeKey.length > 0
      ? this.initialTypeKey
      : (this.eventTypes.length > 0 ? this.eventTypes[0].typeKey : '');
  }

  build() {
    FormDialog({
      palette: this.palette,
      title: '记录事件',
      confirmEnabled: this.typeKey.length > 0,
      onConfirm: () => {
        this.onSave(this.typeKey, this.occurredAt, this.note.trim().length > 0 ? this.note : null);
      },
      onDismiss: this.onDismiss,
      onDelete: null,
    }) {
      Column({ space: 12 }) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.eventTypes, (type: EventTypeEntity) => {
            Row({ space: 6 }) {
              Row()
                .width(8)
                .height(8)
                .borderRadius(4)
                .backgroundColor(colorString(type.colorArgb))
              Text(type.name)
                .fontSize(13)
                .fontColor(this.typeKey === type.typeKey
                  ? this.palette.onSecondaryContainer
                  : this.palette.onSurfaceVariant)
            }
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .borderRadius(16)
            .borderWidth(1)
            .borderColor(this.typeKey === type.typeKey ? '#00000000' : this.palette.outline)
            .backgroundColor(this.typeKey === type.typeKey
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
            .margin({ right: 8, bottom: 8 })
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => {
              this.typeKey = type.typeKey;
            })
          }, (type: EventTypeEntity) => type.typeKey)
        }
        .width('100%')

        DateTimeField({
          palette: this.palette,
          label: '发生时间',
          value: this.occurredAt,
          onChange: (value: number) => {
            this.occurredAt = value;
          },
        })

        TextArea({ placeholder: '备注（可选）', text: this.note })
          .width('100%')
          .height(80)
          .fontSize(14)
          .backgroundColor(this.palette.surfaceContainer)
          .borderRadius(12)
          .onChange((value: string) => {
            this.note = value;
          })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
    }
  }
}
