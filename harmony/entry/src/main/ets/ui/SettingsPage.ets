import { common } from '@kit.AbilityKit';
import { tapFeedback } from '../common/Haptics';
import { picker } from '@kit.CoreFileKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { Palette } from '../common/Palette';
import { AppState } from './AppState';
import { EventTypeEntity, Person } from '../data/Models';
import { AppPreferences } from '../data/AppPreferences';
import { ThemeMode, themeModeLabel } from '../common/ThemeMode';
import { EventColors, colorString } from '../common/EventColors';
import { BackupJson } from '../data/BackupJson';
import { ThemePreferences } from '../common/Theme';
import { FileUtil } from '../common/FileUtil';
import { copyText } from './components/PhotoSource';
import { ConfirmDialog, FormDialog } from './components/Dialogs';
import { EventColorDot } from './components/Common';
import { RebindCandidate, RebindFailure, RebindPlan, WidgetData } from '../common/WidgetData';

/**
 * 设置页：人物、事件类型、外观、数据、关于。
 * 数据区的备份与同步入口与 Android 版一致。
 */
@Component
export struct SettingsPage {
  @Prop palette: Palette;
  @ObjectLink state: AppState;
  onOpenSendDialog: () => void = () => {
  };
  onThemeModeChange: (mode: ThemeMode) => void = (_mode: ThemeMode) => {
  };
  onOpenReceiveDialog: () => void = () => {
  };
  onOpenGuide: () => void = () => {
  };

  @State private addingPerson: boolean = false;
  @State private renamingPerson: Person | null = null;
  @State private deletingPerson: Person | null = null;
  @State private addingEventType: boolean = false;
  @State private editingEventType: EventTypeEntity | null = null;
  @State private deletingEventType: EventTypeEntity | null = null;
  @State private deletingEventTypeCount: number = 0;
  @State private pendingImportText: string | null = null;
  @State private pendingImportSummary: string = '';
  /** 导入备份后的卡片恢复候选；非空时弹出确认弹窗。 */
  @State private rebindPlan: RebindPlan | null = null;
  @State private watermark: boolean = false;
  @State private checkNumber: boolean = false;
  @State private busy: boolean = false;

  aboutToAppear(): void {
    this.watermark = AppPreferences.watermarkEnabled();
    this.checkNumber = AppPreferences.checkNumberAfterUpload();
  }

  private defaultEventColor(): number {
    const used: Set<number> = new Set<number>();
    const types: EventTypeEntity[] = this.state.eventTypes;
    for (let i = 0; i < types.length; i++) {
      used.add(types[i].colorArgb);
    }
    const colors: number[] = EventColors.palette();
    for (let i = 0; i < colors.length; i++) {
      if (!used.has(colors[i])) {
        return colors[i];
      }
    }
    return colors[0];
  }

  private actionIconPath(isDelete: boolean): string {
    const path = isDelete
      ? 'M5 7 H19 M8 7 V5 H16 V7 M7 7 L8 20 H16 L17 7 M10 10 V17 M14 10 V17'
      : 'M4 20 L8 19 L19 8 C20 7 20 6 19 5 L18 4 C17 3 16 3 15 4 L4 15 Z M13 6 L17 10';
    const density = vp2px(1);
    return path.replace(new RegExp('\\d+', 'g'), (value: string): string => `${Number(value) * density}`);
  }

  @Builder
  ActionIcon(isDelete: boolean, onActivate: () => void) {
    Button() {
      Path({ width: 24, height: 24, commands: this.actionIconPath(isDelete) })
        .fillOpacity(0)
        .stroke(this.palette.onSurfaceVariant)
        .strokeWidth(1.8)
        .strokeLineCap(LineCapStyle.Round)
        .strokeLineJoin(LineJoinStyle.Round)
    }
    .width(44)
    .height(44)
    .borderRadius(22)
    .backgroundColor(Color.Transparent)
    .accessibilityText(isDelete ? '删除' : '编辑')
    .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.94 })
    .onClick(() => {
      tapFeedback();
      onActivate();
    })
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
    Scroll() {
      Column({ space: 16 }) {
        Text('设置')
          .fontSize(28)
          .fontFamily('HarmonyOS Sans')
          .fontWeight(FontWeight.Bold)
          .fontColor(this.palette.onSurface)
          .width('100%')

        this.PersonsCard()
        this.EventTypesCard()
        this.AppearanceCard()
        this.DataCard()
        this.AboutCard()
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 16, bottom: 112 })
    }
    .scrollBar(BarState.Off)
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette.background)

    if (this.addingPerson) {
      PersonNameDialog({
        palette: this.palette,
        title: '新建人物',
        initialName: '',
        takenNames: this.state.persons.map((p: Person) => p.name),
        onDismiss: () => {
          this.addingPerson = false;
        },
        onSave: (name: string) => {
          this.addingPerson = false;
          this.state.addPerson(name);
        },
      })
    }

    if (this.renamingPerson !== null) {
      PersonNameDialog({
        palette: this.palette,
        title: `给「${this.renamingPerson!.name}」改名`,
        initialName: this.renamingPerson!.name,
        takenNames: this.state.persons
          .filter((p: Person) => p.id !== this.renamingPerson!.id)
          .map((p: Person) => p.name),
        onDismiss: () => {
          this.renamingPerson = null;
        },
        onSave: (name: string) => {
          const person = this.renamingPerson!;
          this.renamingPerson = null;
          this.state.renamePerson(person, name);
        },
      })
    }

    if (this.deletingPerson !== null) {
      ConfirmDialog({
        palette: this.palette,
        title: `删除「${this.deletingPerson!.name}」？`,
        message: '该人物名下的所有分段和事件将一并删除，且无法恢复。',
        confirmText: '删除',
        icon: '🗑',
        tone: 'danger',
        onConfirm: () => {
          const person = this.deletingPerson!;
          this.deletingPerson = null;
          this.state.deletePerson(person);
        },
        onDismiss: () => {
          this.deletingPerson = null;
        },
      })
    }

    if (this.addingEventType) {
      EventTypeEditDialog({
        palette: this.palette,
        title: '新建事件类型',
        initialName: '',
        initialColorArgb: this.defaultEventColor(),
        takenNames: this.state.eventTypes.map((t: EventTypeEntity) => t.name),
        onDismiss: () => {
          this.addingEventType = false;
        },
        onSave: (name: string, colorArgb: number) => {
          this.addingEventType = false;
          this.state.addEventType(name, colorArgb);
        },
      })
    }

    if (this.editingEventType !== null) {
      EventTypeEditDialog({
        palette: this.palette,
        title: `编辑「${this.editingEventType!.name}」`,
        initialName: this.editingEventType!.name,
        initialColorArgb: this.editingEventType!.colorArgb,
        takenNames: this.state.eventTypes
          .filter((t: EventTypeEntity) => t.id !== this.editingEventType!.id)
          .map((t: EventTypeEntity) => t.name),
        onDismiss: () => {
          this.editingEventType = null;
        },
        onSave: (name: string, colorArgb: number) => {
          const type = this.editingEventType!;
          this.editingEventType = null;
          this.state.updateEventType(type, name, colorArgb);
        },
      })
    }

    if (this.deletingEventType !== null) {
      ConfirmDialog({
        palette: this.palette,
        title: `删除「${this.deletingEventType!.name}」？`,
        message: `该类型名下有 ${this.deletingEventTypeCount} 条事件（所有人物），将一并删除，且无法恢复。`,
        confirmText: '删除',
        icon: '🗑',
        tone: 'danger',
        onConfirm: () => {
          const type = this.deletingEventType!;
          this.deletingEventType = null;
          this.state.deleteEventType(type);
        },
        onDismiss: () => {
          this.deletingEventType = null;
        },
      })
    }

    if (this.pendingImportText !== null) {
      ConfirmDialog({
        palette: this.palette,
        title: '替换全部数据？',
        message: `${this.pendingImportSummary}导入会覆盖当前所有数据并清空每日照片，且无法撤销。`,
        confirmText: '覆盖导入',
        icon: '⚠',
        tone: 'danger',
        onConfirm: () => {
          const text = this.pendingImportText!;
          this.pendingImportText = null;
          this.state.importBackup(text).then(() => {
            this.state.notify('导入完成');
            // 导入会重建人物，卡片绑定需要按名称确认恢复
            this.planRebind();
          }).catch((err: Error) => {
            this.state.notify(err.message ?? '导入失败');
          });
        },
        onDismiss: () => {
          this.pendingImportText = null;
        },
      })
    }

    if (this.rebindPlan !== null) {
      RebindDialog({
        palette: this.palette,
        plan: this.rebindPlan!,
        onDismiss: () => {
          this.rebindPlan = null;
        },
        onRebind: (candidates: RebindCandidate[]) => {
          const context = getContext(this) as common.UIAbilityContext;
          WidgetData.applyRebind(context, candidates);
          // refreshAll 内部已吞掉刷新异常，这里只需提示改绑结果
          WidgetData.refreshAll(context).then(() => {
            this.state.notify('小组件已重新绑定');
          });
        },
      })
    }
    }
  }

  @Builder
  PersonsCard() {
    Column({ space: 8 }) {
      Row() {
        Text('人物')
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.palette.onSurface)
        Blank()
        Button() {
          Text('+')
            .fontSize(22)
            .fontColor(this.palette.primary)
        }
        .width(36)
        .height(36)
        .borderRadius(18)
        .backgroundColor(this.palette.surfaceContainerHigh)
        .borderWidth(1)
        .borderColor(this.palette.outlineVariant)
        .accessibilityText('新建人物')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.94 })
        .onClick(() => {
          tapFeedback();
          this.addingPerson = true;
        })
      }
      .width('100%')

      Text('每个人物独立计时，可以同时佩戴。切换人物后，主页和统计都会跟随。')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')

      ForEach(this.state.persons, (person: Person, index: number) => {
        Row({ space: 8 }) {
          Row()
            .width(18)
            .height(18)
            .borderRadius(9)
            .borderWidth(2)
            .borderColor(person.id === this.state.currentPerson?.id
              ? this.palette.primary
              : this.palette.outline)
            .backgroundColor(person.id === this.state.currentPerson?.id
              ? this.palette.primary
              : Color.Transparent)
            .animation({ duration: 200 })
          Column() {
            Text(person.name)
              .fontSize(16)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.palette.onSurface)
              .width('100%')
            if (person.id === this.state.currentPerson?.id) {
              Text('当前人物')
                .fontSize(11)
                .fontColor(this.palette.primary)
                .width('100%')
            }
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)

          this.ActionIcon(false, () => {
            this.renamingPerson = person;
          })
          if (this.state.persons.length > 1) {
            this.ActionIcon(true, () => {
              this.deletingPerson = person;
            })
          }
        }
        .width('100%')
        .padding({ top: 4, bottom: 4 })
        .alignItems(VerticalAlign.Center)
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .onClick(() => {
          this.state.selectPerson(person);
        })
      }, (person: Person) => `${person.id}`)
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
    .alignItems(HorizontalAlign.Start)
    .animation({ duration: 250, curve: Curve.EaseOut })
  }

  @Builder
  EventTypesCard() {
    Column({ space: 8 }) {
      Row() {
        Text('事件类型')
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor(this.palette.onSurface)
        Blank()
        Button() {
          Text('+')
            .fontSize(22)
            .fontColor(this.palette.primary)
        }
        .width(36)
        .height(36)
        .borderRadius(18)
        .backgroundColor(this.palette.surfaceContainerHigh)
        .borderWidth(1)
        .borderColor(this.palette.outlineVariant)
        .accessibilityText('新建事件类型')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.94 })
        .onClick(() => {
          tapFeedback();
          this.addingEventType = true;
        })
      }
      .width('100%')

      Text('自定义名称和统计颜色；内置类型可改名改色，但不能删除。')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')

      ForEach(this.state.eventTypes, (type: EventTypeEntity) => {
        Row({ space: 10 }) {
          EventColorDot(type.colorArgb, 12)
          Column() {
            Text(type.name)
              .fontSize(16)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.palette.onSurface)
              .width('100%')
            if (type.builtIn) {
              Text('内置')
                .fontSize(11)
                .fontColor(this.palette.onSurfaceVariant)
                .width('100%')
            }
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)

          this.ActionIcon(false, () => {
            this.editingEventType = type;
          })
          if (!type.builtIn) {
            this.ActionIcon(true, () => {
              this.deletingEventType = type;
              this.state.countEventsOfType(type.typeKey).then((count) => {
                this.deletingEventTypeCount = count;
              });
            })
          }
        }
        .width('100%')
        .padding({ top: 4, bottom: 4 })
        .alignItems(VerticalAlign.Center)
      }, (type: EventTypeEntity) => type.typeKey)
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
    .alignItems(HorizontalAlign.Start)
    .animation({ duration: 250, curve: Curve.EaseOut })
  }

  @Builder
  AppearanceCard() {
    Column({ space: 12 }) {
      Text('外观')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.palette.onSurface)
        .width('100%')

      Row({ space: 8 }) {
        ForEach([ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK], (mode: ThemeMode) => {
          Text(themeModeLabel(mode))
            .fontSize(13)
            .fontColor(this.state.themeMode === mode
              ? this.palette.onSecondaryContainer
              : this.palette.onSurfaceVariant)
            .padding({ left: 16, right: 16, top: 6, bottom: 6 })
            .borderRadius(16)
            .borderWidth(1)
            .borderColor(this.state.themeMode === mode ? '#00000000' : this.palette.outline)
            .backgroundColor(this.state.themeMode === mode
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
            // 选中态的文字色与背景色平滑过渡
            .animation({ duration: 250 })
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => {
              this.state.setThemeMode(mode);
              const context = getContext(this) as common.UIAbilityContext;
              context.getApplicationContext().setColorMode(ThemePreferences.toColorMode(mode));
              this.onThemeModeChange(mode);
            })
        }, (mode: ThemeMode) => mode)
      }
      .width('100%')

      Text(`当前：${themeModeLabel(this.state.themeMode)}`)
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
    .alignItems(HorizontalAlign.Start)
    .animation({ duration: 250, curve: Curve.EaseOut })
  }

  @Builder
  DataCard() {
    Column({ space: 12 }) {
      Text('数据')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.palette.onSurface)
        .width('100%')

      Text('所有人物、事件类型、分段、事件和每日随机数都只保存在本机；' +
        '每日随机数会进入 JSON 备份。每日照片只保存在本机，且不包含在 JSON 备份中。' +
        '换手机或重装前，请先导出备份。')
        .fontSize(13)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')

      Button('导出备份')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .width('100%')
        .height(46)
        .fontSize(15)
        .fontColor(this.palette.onPrimary)
        .backgroundColor(this.palette.primary)
        .borderRadius(23)
        .enabled(!this.busy)
        .onClick(() => this.exportBackup())

      Button('导入备份')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .width('100%')
        .height(46)
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
        .borderRadius(23)
        .enabled(!this.busy)
        .onClick(() => this.importBackup())

      Divider().color(this.palette.outlineVariant)

      Text('掌管者同步')
        .fontSize(16)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.palette.onSurface)
        .width('100%')

      Text('把当前人物所选范围内的分段、事件、随机数和照片打包加密发送给对方；' +
        '接收时需要验证对方告诉你的身份码。')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')

      Button('发送给掌管者')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .width('100%')
        .height(46)
        .fontSize(15)
        .fontColor(this.palette.onPrimary)
        .backgroundColor(this.palette.primary)
        .borderRadius(23)
        .enabled(this.state.currentPerson !== null)
        .onClick(() => this.onOpenSendDialog())

      Button('接收掌管者数据')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .width('100%')
        .height(46)
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
        .borderRadius(23)
        .onClick(() => this.onOpenReceiveDialog())

      Divider().color(this.palette.outlineVariant)

      Row() {
        Column() {
          Text('上传照片时烧录时间水印')
            .fontSize(14)
            .fontColor(this.palette.onSurface)
            .width('100%')
          Text('在照片右下角烧录上传时间，便于对方核验。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        Toggle({ type: ToggleType.Switch, isOn: this.watermark })
          .onChange((isOn: boolean) => {
            this.watermark = isOn;
            AppPreferences.setWatermarkEnabled(isOn);
          })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Row() {
        Column() {
          Text('上传照片后检测随机数')
            .fontSize(14)
            .fontColor(this.palette.onSurface)
            .width('100%')
          Text('上传完成后自动识别照片中的号码，与当日随机数比对并提示结果。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        Toggle({ type: ToggleType.Switch, isOn: this.checkNumber })
          .onChange((isOn: boolean) => {
            this.checkNumber = isOn;
            AppPreferences.setCheckNumberAfterUpload(isOn);
          })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Text(`当前人物：${this.state.currentPerson?.name ?? '—'} · ` +
        `${this.state.segmentCount} 个分段，${this.state.eventCount} 条事件`)
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
    .alignItems(HorizontalAlign.Start)
    .animation({ duration: 250, curve: Curve.EaseOut })
  }

  @Builder
  AboutCard() {
    Column({ space: 8 }) {
      Text('关于')
        .fontSize(18)
        .fontWeight(FontWeight.Bold)
        .fontColor(this.palette.onSurface)
        .width('100%')
      Text('Locker 2.2.5')
        .fontSize(15)
        .fontColor(this.palette.onSurface)
        .width('100%')
      Text('本地离线的多人物佩戴计时与事件记录工具。无网络权限、无账号、无云端同步。')
        .fontSize(13)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
      Button('使用指南')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .width('100%')
        .height(46)
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
        .borderRadius(23)
        .onClick(() => this.onOpenGuide())

      Divider().color(this.palette.outlineVariant)

      Text('开发与维护')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
      Row() {
        Column() {
          Text('yelans')
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.palette.onSurface)
            .width('100%')
          Text('QQ 3781994974')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
        }
        .layoutWeight(1)
        .alignItems(HorizontalAlign.Start)
        Text('复制 QQ')
          .fontSize(13)
          .fontColor(this.palette.primary)
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .onClick(() => copyText('3781994974', 'QQ 号已复制'))
      }
      .width('100%')
    }
    .width('100%')
    .padding(16)
    .backgroundColor(this.palette.surface)
    .borderRadius(20)
    .alignItems(HorizontalAlign.Start)
    .animation({ duration: 250, curve: Curve.EaseOut })
  }

  // ---------------- 备份 ----------------

  private async exportBackup(): Promise<void> {
    this.busy = true;
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const text = await this.state.buildBackupText();
      const stamp = this.stamp();
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [`locker-backup-${stamp}.json`];
      options.fileSuffixChoices = ['.json'];
      const documentPicker = new picker.DocumentViewPicker(context);
      const uris = await documentPicker.save(options);
      if (uris.length === 0) {
        return;
      }
      const file = fs.openSync(uris[0], fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      try {
        fs.writeSync(file.fd, text);
      } finally {
        fs.closeSync(file);
      }
      this.state.notify('备份已导出');
    } catch (err) {
      this.state.notify((err as Error).message ?? '导出失败');
    } finally {
      this.busy = false;
    }
  }

  private async importBackup(): Promise<void> {
    this.busy = true;
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['.json', '.txt'];
      const documentPicker = new picker.DocumentViewPicker(context);
      const uris = await documentPicker.select(options);
      if (uris.length === 0) {
        return;
      }
      const text = FileUtil.readText(uris[0]);
      if (text.length > 20_000_000) {
        throw new Error('备份文件过大');
      }
      const data = BackupJson.decode(text);
      this.pendingImportSummary =
        `备份包含 ${data.persons.length} 个人物、${data.eventTypes.length} 种事件类型、` +
          `${data.segments.length} 个分段、${data.events.length} 条事件、` +
          `${data.dailyRandomNumbers.length} 条每日随机数。`;
      this.pendingImportText = text;
    } catch (err) {
      this.state.notify((err as Error).message ?? '导入失败');
    } finally {
      this.busy = false;
    }
  }

  /** 读取导入后的卡片恢复候选；有可恢复的卡片才弹窗。 */
  private planRebind(): void {
    const context = getContext(this) as common.UIAbilityContext;
    WidgetData.planRebind(context).then((plan: RebindPlan) => {
      if (plan.candidates.length > 0 || plan.failures.length > 0) {
        this.rebindPlan = plan;
      }
    }).catch((err: Error) => {
      this.state.notify(err.message ?? '读取小组件绑定失败');
    });
  }

  private stamp(): string {
    const d = new Date();
    return `${d.getFullYear()}${(d.getMonth() + 1).toString().padStart(2, '0')}` +
      `${d.getDate().toString().padStart(2, '0')}-` +
      `${d.getHours().toString().padStart(2, '0')}${d.getMinutes().toString().padStart(2, '0')}`;
  }
}

/** 人物命名弹窗：名称不能为空、不能重名、最多 20 字符。 */
@Component
export struct PersonNameDialog {
  @Prop palette: Palette;
  @Prop title: string;
  @Prop initialName: string;
  @Prop takenNames: string[] = [];
  onDismiss: () => void = () => {
  };
  onSave: (name: string) => void = () => {
  };

  @State private name: string = '';

  aboutToAppear(): void {
    this.name = this.initialName;
  }

  private trimmed(): string {
    return this.name.trim();
  }

  private duplicate(): boolean {
    return this.trimmed().length > 0 && this.takenNames.indexOf(this.trimmed()) >= 0;
  }

  private empty(): boolean {
    return this.trimmed().length === 0 && this.name.length > 0;
  }

  private hint(): string {
    if (this.duplicate()) {
      return '已存在同名人物';
    }
    if (this.empty()) {
      return '名称不能为空';
    }
    return '最多 20 个字符';
  }

  build() {
    FormDialog({
      palette: this.palette,
      title: this.title,
      confirmEnabled: this.trimmed().length > 0 && !this.duplicate(),
      onConfirm: () => this.onSave(this.trimmed()),
      onDismiss: this.onDismiss,
      onDelete: null,
    }) {
      Column({ space: 6 }) {
        TextInput({ placeholder: '名称', text: this.name })
          .width('100%')
          .height(48)
          .fontSize(15)
          .backgroundColor(this.palette.surfaceContainer)
          .borderRadius(12)
          .onChange((value: string) => {
            this.name = value.substring(0, 20);
          })
        Text(this.hint())
          .fontSize(12)
          .fontColor(this.duplicate() || this.empty()
            ? this.palette.error
            : this.palette.onSurfaceVariant)
          .width('100%')
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
    }
  }
}

/** 事件类型编辑弹窗：名称 + 色板。 */
@Component
export struct EventTypeEditDialog {
  @Prop palette: Palette;
  @Prop title: string;
  @Prop initialName: string;
  @Prop initialColorArgb: number;
  @Prop takenNames: string[] = [];
  onDismiss: () => void = () => {
  };
  onSave: (name: string, colorArgb: number) => void = () => {
  };

  @State private name: string = '';
  @State private colorArgb: number = 0;

  aboutToAppear(): void {
    this.name = this.initialName;
    this.colorArgb = this.initialColorArgb;
  }

  private trimmed(): string {
    return this.name.trim();
  }

  private duplicate(): boolean {
    return this.trimmed().length > 0 && this.takenNames.indexOf(this.trimmed()) >= 0;
  }

  private empty(): boolean {
    return this.trimmed().length === 0 && this.name.length > 0;
  }

  build() {
    FormDialog({
      palette: this.palette,
      title: this.title,
      confirmEnabled: this.trimmed().length > 0 && !this.duplicate(),
      onConfirm: () => this.onSave(this.trimmed(), this.colorArgb),
      onDismiss: this.onDismiss,
      onDelete: null,
    }) {
      Column({ space: 12 }) {
        TextInput({ placeholder: '名称', text: this.name })
          .width('100%')
          .height(48)
          .fontSize(15)
          .backgroundColor(this.palette.surfaceContainer)
          .borderRadius(12)
          .onChange((value: string) => {
            this.name = value.substring(0, 20);
          })
        Text(this.duplicate()
          ? '已存在同名事件类型'
          : (this.empty() ? '名称不能为空' : '最多 20 个字符'))
          .fontSize(12)
          .fontColor(this.duplicate() || this.empty()
            ? this.palette.error
            : this.palette.onSurfaceVariant)
          .width('100%')

        Text('统计颜色')
          .fontSize(13)
          .fontColor(this.palette.onSurfaceVariant)
          .width('100%')

        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(EventColors.palette(), (color: number) => {
            Row()
              .width(32)
              .height(32)
              .borderRadius(16)
              .backgroundColor(colorString(color))
              .borderWidth(this.colorArgb === color ? 3 : 0)
              .borderColor(this.palette.onSurface)
              .margin({ right: 10, bottom: 10 })
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => {
                this.colorArgb = color;
              })
          }, (color: number) => `${color}`)
        }
        .width('100%')

        Row({ space: 8 }) {
          EventColorDot(this.colorArgb, 12)
          Text(this.trimmed().length > 0 ? this.trimmed() : '预览')
            .fontSize(14)
            .fontColor(this.palette.onSurface)
        }
        .alignItems(VerticalAlign.Center)

        Text('该颜色用于统计图表、月历圆点和事件列表。')
          .fontSize(12)
          .fontColor(this.palette.onSurfaceVariant)
          .width('100%')
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
    }
  }
}

/**
 * 导入备份后的卡片重新绑定弹窗。
 *
 * 导入会重建人物、id 全变，卡片绑定全部失效。这里只列出「按名称能找回」的卡片，
 * 由用户确认后统一改绑；找不到人物的卡片单独标红说明，不会静默改绑。
 */
@Component
export struct RebindDialog {
  @Prop palette: Palette;
  @Prop plan: RebindPlan;
  onDismiss: () => void = () => {
  };
  onRebind: (candidates: RebindCandidate[]) => void = () => {
  };

  @State private appear: number = 0;
  @State private closing: boolean = false;

  /** 收起弹窗：先缩回淡出，再回调 onDismiss。 */
  private dismiss(then: (() => void) | null = null): void {
    if (this.closing) {
      return;
    }
    this.closing = true;
    animateTo({ duration: 160, curve: Curve.EaseIn }, () => {
      this.appear = 0;
    });
    setTimeout(() => {
      if (then !== null) {
        then();
      }
      this.onDismiss();
    }, 160);
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
        Column() {
          Row() {
            Text('桌面小组件')
              .fontSize(22)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.palette.onSurface)
              .layoutWeight(1)
            Text('🧩')
              .fontSize(22)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ bottom: 8 })

          Text('导入备份后人物编号会变化，以下小组件可以按名称重新绑定。')
            .fontSize(13)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .margin({ bottom: 12 })

          ForEach(this.plan.candidates, (candidate: RebindCandidate) => {
            Text(`· 「${candidate.personName}」的小组件`)
              .fontSize(14)
              .fontColor(this.palette.onSurface)
              .width('100%')
              .margin({ bottom: 4 })
          }, (candidate: RebindCandidate) => candidate.formId)

          if (this.plan.failures.length > 0) {
            Text('无法自动恢复')
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.palette.error)
              .width('100%')
              .margin({ top: 10, bottom: 4 })
            ForEach(this.plan.failures, (failure: RebindFailure) => {
              Text(`· 「${failure.personName.length > 0 ? failure.personName : '未知人物'}」的小组件（人物已不存在）`)
                .fontSize(12)
                .fontColor(this.palette.onSurfaceVariant)
                .width('100%')
                .margin({ bottom: 4 })
            }, (failure: RebindFailure) => failure.formId)
          }

          Row({ space: 12 }) {
            Button('跳过')
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(48)
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
              .borderRadius(24)
              .onClick(() => this.dismiss())

            if (this.plan.candidates.length > 0) {
              Button('全部重新绑定')
                .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
                .layoutWeight(1)
                .height(48)
                .fontSize(15)
                .fontColor(this.palette.onPrimary)
                .backgroundColor(this.palette.primary)
                .borderRadius(24)
                .onClick(() => {
                  const candidates = this.plan.candidates;
                  this.dismiss(() => this.onRebind(candidates));
                })
            }
          }
          .width('100%')
          .margin({ top: 20 })
        }
        .width('100%')
        .padding({ left: 24, right: 24, top: 28, bottom: 24 })
        .backgroundColor(this.palette.surface)
        .borderRadius(28)
        .scale({ x: 0.86 + 0.14 * this.appear, y: 0.86 + 0.14 * this.appear })
        .opacity(this.appear)
        .alignItems(HorizontalAlign.Start)
      }
      .constraintSize({ maxWidth: 360, maxHeight: '86%' })
      .margin({ left: 24, right: 24 })
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .onAppear(() => {
      animateTo({ duration: 260, curve: Curve.Friction }, () => {
        this.appear = 1;
      });
    })
  }
}
