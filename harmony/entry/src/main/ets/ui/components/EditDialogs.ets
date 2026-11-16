import { Palette } from '../../common/Palette';
import { tapFeedback } from '../../common/Haptics';
import { EventRecord, EventTypeEntity, WearSegment } from '../../data/Models';
import { formatDateCompact, formatTime } from '../../common/TimeFormat';
import { colorString } from '../../common/EventColors';
import { ConfirmDialog, FormDialog } from './Dialogs';

/**
 * 分段与事件的编辑弹窗。
 *
 * 每个组件都以 Stack 作为唯一根节点：弹窗本体 + 需要叠加的确认框都在里面。
 * 表单内容用 ArkUI 的尾随闭包注入 FormDialog。
 */

@Component
export struct SegmentEditDialog {
  @Prop palette: Palette;
  initial: WearSegment | null = null;
  onDismiss: () => void = () => {
  };
  onSave: (startAt: number, endAt: number | null) => void = () => {
  };
  onDelete: (() => void) | null = null;

  @State private startAt: number = Date.now();
  @State private endAt: number = Date.now();
  @State private stillWearing: boolean = false;
  @State private confirmDelete: boolean = false;

  aboutToAppear(): void {
    const now = Date.now();
    if (this.initial === null) {
      this.startAt = now - 60 * 60 * 1000;
      this.endAt = now;
      this.stillWearing = false;
    } else {
      this.startAt = this.initial.startAt;
      this.stillWearing = this.initial.endAt === null;
      this.endAt = this.initial.endAt === null ? now : this.initial.endAt;
    }
  }

  private invalidEnd(): boolean {
    return !this.stillWearing && this.endAt < this.startAt;
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      FormDialog({
        palette: this.palette,
        title: this.initial === null ? '补录分段' : '编辑分段',
        confirmEnabled: !this.invalidEnd(),
        onConfirm: () => {
          this.onSave(this.startAt, this.stillWearing ? null : this.endAt);
        },
        onDismiss: this.onDismiss,
        onDelete: this.onDelete === null ? null : () => {
          this.confirmDelete = true;
        },
      }) {
        Column({ space: 12 }) {
          DateTimeField({
            palette: this.palette,
            label: '戴上时间',
            value: this.startAt,
            onChange: (value: number) => {
              this.startAt = value;
              if (!this.stillWearing && this.endAt < value) {
                this.endAt = value;
              }
            },
          })

          Row() {
            Text('仍在佩戴中')
              .fontSize(14)
              .fontColor(this.palette.onSurface)
              .layoutWeight(1)
            Toggle({ type: ToggleType.Switch, isOn: this.stillWearing })
              .onChange((isOn: boolean) => {
                this.stillWearing = isOn;
                if (!isOn) {
                  this.endAt = Math.max(Date.now(), this.startAt);
                }
              })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)

          if (!this.stillWearing) {
            DateTimeField({
              palette: this.palette,
              label: '取下时间',
              value: this.endAt,
              onChange: (value: number) => {
                this.endAt = value;
              },
            })
          }

          if (this.invalidEnd()) {
            Text('取下时间不能早于戴上时间')
              .fontSize(12)
              .fontColor(this.palette.error)
              .width('100%')
          }

          Text('分段之间不能重叠；补录或修改会自动校验。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
      }

      if (this.confirmDelete) {
        ConfirmDialog({
          palette: this.palette,
          title: '删除这个分段？',
          message: '删除后，这段佩戴时长将从统计中移除，且无法恢复。',
          confirmText: '删除',
          icon: '🗑',
          tone: 'danger',
          onConfirm: () => {
            this.confirmDelete = false;
            if (this.onDelete !== null) {
              this.onDelete();
            }
          },
          onDismiss: () => {
            this.confirmDelete = false;
          },
        })
      }
    }
    .width('100%')
    .height('100%')
  }
}

/** 统计页的事件编辑弹窗：类型 + 时间 + 备注，右上角可删除。 */
@Component
export struct EventEditDialog {
  @Prop palette: Palette;
  initial: EventRecord = { id: 0, personId: 0, type: '', occurredAt: 0, note: null };
  @Prop eventTypes: EventTypeEntity[] = [];
  onDismiss: () => void = () => {
  };
  onSave: (typeKey: string, occurredAt: number, note: string | null) => void = () => {
  };
  onDelete: () => void = () => {
  };

  @State private typeKey: string = '';
  @State private occurredAt: number = Date.now();
  @State private note: string = '';
  @State private confirmDelete: boolean = false;

  aboutToAppear(): void {
    this.typeKey = this.initial.type;
    this.occurredAt = this.initial.occurredAt;
    this.note = this.initial.note ?? '';
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      FormDialog({
        palette: this.palette,
        title: '编辑事件',
        confirmEnabled: this.typeKey.length > 0,
        onConfirm: () => {
          this.onSave(this.typeKey, this.occurredAt,
            this.note.trim().length > 0 ? this.note : null);
        },
        onDismiss: this.onDismiss,
        onDelete: () => {
          this.confirmDelete = true;
        },
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

      if (this.confirmDelete) {
        ConfirmDialog({
          palette: this.palette,
          title: '删除这条事件？',
          message: '删除后无法恢复。',
          confirmText: '删除',
          icon: '🗑',
          tone: 'danger',
          onConfirm: () => {
            this.confirmDelete = false;
            this.onDelete();
          },
          onDismiss: () => {
            this.confirmDelete = false;
          },
        })
      }
    }
    .width('100%')
    .height('100%')
  }
}

/** 日期 + 时间两段式编辑；点「现在」直接跳到当前时刻。 */
@Component
export struct DateTimeField {
  @Prop palette: Palette;
  @Prop label: string;
  @Prop value: number;
  onChange: (value: number) => void = () => {
  };

  @State private showDatePicker: boolean = false;
  @State private showTimePicker: boolean = false;
  @State private pickerAppear: number = 0;
  private pickerClosing: boolean = false;

  private dismissPicker(): void {
    if (this.pickerClosing) {
      return;
    }
    this.pickerClosing = true;
    animateTo({ duration: 160, curve: Curve.EaseIn, onFinish: () => {
      this.showDatePicker = false;
      this.showTimePicker = false;
      this.pickerClosing = false;
    } }, () => {
      this.pickerAppear = 0;
    });
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Column({ space: 6 }) {
        Row() {
          Text(this.label)
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
          Blank()
          Text('现在')
            .fontSize(13)
            .fontColor(this.palette.primary)
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => this.onChange(Date.now()))
        }
        .width('100%')

        Row({ space: 8 }) {
          Button(formatDateCompact(this.value))
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .layoutWeight(1.3)
            .height(40)
            .fontSize(13)
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
            .borderRadius(20)
            .onClick(() => {
              this.pickerAppear = 0;
              this.showDatePicker = true;
            })
          Button(formatTime(this.value))
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .layoutWeight(1)
            .height(40)
            .fontSize(13)
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
            .borderRadius(20)
            .onClick(() => {
              this.pickerAppear = 0;
              this.showTimePicker = true;
            })
        }
        .width('100%')
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)

      if (this.showDatePicker) {
        this.DateDialog()
      }
      if (this.showTimePicker) {
        this.TimeDialog()
      }
    }
    .width('100%')
  }

  @Builder
  DateDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#66000000')
        .opacity(this.pickerAppear)
        .onClick(() => this.dismissPicker())
      Column({ space: 12 }) {
        Text('选择日期')
          .fontSize(20)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.palette.onSurface)
          .width('100%')
        DatePicker({
          start: new Date(2000, 0, 1),
          end: new Date(2100, 11, 31),
          selected: new Date(this.value),
        })
          .onDateChange((picked: Date) => {
            const base = new Date(this.value);
            const next = new Date(
              picked.getFullYear(),
              picked.getMonth(),
              picked.getDate(),
              base.getHours(),
              base.getMinutes(),
            );
            this.onChange(next.getTime());
          })
        Button('确定')
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .width('100%')
          .height(44)
          .fontSize(15)
          .fontColor(this.palette.onPrimary)
          .backgroundColor(this.palette.primary)
          .borderRadius(22)
          .onClick(() => this.dismissPicker())
      }
      .width('86%')
      .padding(24)
      .backgroundColor(this.palette.surface)
      .borderRadius(24)
      .opacity(this.pickerAppear)
      .scale({ x: 0.96 + 0.04 * this.pickerAppear, y: 0.96 + 0.04 * this.pickerAppear })
    }
    .width('100%')
    .height('100%')
    .onAppear(() => {
      animateTo({ duration: 260, curve: Curve.FastOutSlowIn }, () => {
        this.pickerAppear = 1;
      });
    })
  }

  @Builder
  TimeDialog() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#66000000')
        .opacity(this.pickerAppear)
        .onClick(() => this.dismissPicker())
      Column({ space: 12 }) {
        Text('选择时间')
          .fontSize(20)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.palette.onSurface)
          .width('100%')
        TimePicker({
          selected: new Date(this.value),
          format: TimePickerFormat.HOUR_MINUTE,
        })
          .onChange((picked: TimePickerResult) => {
            const base = new Date(this.value);
            const next = new Date(
              base.getFullYear(),
              base.getMonth(),
              base.getDate(),
              picked.hour,
              picked.minute,
            );
            this.onChange(next.getTime());
          })
        Button('确定')
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .width('100%')
          .height(44)
          .fontSize(15)
          .fontColor(this.palette.onPrimary)
          .backgroundColor(this.palette.primary)
          .borderRadius(22)
          .onClick(() => this.dismissPicker())
      }
      .width('86%')
      .padding(24)
      .backgroundColor(this.palette.surface)
      .borderRadius(24)
      .opacity(this.pickerAppear)
      .scale({ x: 0.96 + 0.04 * this.pickerAppear, y: 0.96 + 0.04 * this.pickerAppear })
    }
    .width('100%')
    .height('100%')
    .onAppear(() => {
      animateTo({ duration: 260, curve: Curve.FastOutSlowIn }, () => {
        this.pickerAppear = 1;
      });
    })
  }
}
