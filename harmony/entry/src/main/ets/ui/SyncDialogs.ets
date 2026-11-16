import { common } from '@kit.AbilityKit';
import { tapFeedback } from '../common/Haptics';
import { picker } from '@kit.CoreFileKit';
import { Palette } from '../common/Palette';
import { AppState } from './AppState';
import {
  DayWindow,
  SyncRange,
  incrementalWindow,
  parseSyncRange,
  syncRangeLabel,
  syncRangeValues,
  windowFor,
  windowLabel,
} from '../domain/SyncRange';
import { todayEpochDay } from '../common/Dates';
import { formatMonthDayTime, formatBytes } from '../common/TimeFormat';
import { AppPreferences } from '../data/AppPreferences';
import { FileUtil } from '../common/FileUtil';
import { SyncAction, SyncChange, defaultSelectedSyncKeys } from '../data/SyncDiff';
import { ConfirmDialog } from './components/Dialogs';
import { copyText, shareFile } from './components/PhotoSource';
import { ImportDiff, ImportPreview, SyncService } from './SyncService';

/**
 * 发送弹窗：显示身份码、选择范围、增量发送、打包并分享。
 *
 * 每个弹窗都以一个 Stack 作为唯一根节点：遮罩、内容卡片、
 * 以及需要叠加的确认弹窗都放在里面，避免 ArkUI 的「单根节点」限制。
 */
@Component
export struct SendSyncDialog {
  @Prop palette: Palette;
  @ObjectLink state: AppState;
  onDismiss: () => void = () => {
  };

  @State private code: string = '';
  @State private lastExportedAt: number | null = null;
  @State private range: SyncRange = SyncRange.TODAY;
  @State private incremental: boolean = false;
  @State private preserveMoving: boolean = true;
  @State private photoCount: number = 0;
  @State private photoBytes: number = 0;
  @State private busy: boolean = false;
  @State private progress: number = 0;
  @State private confirmChangeCode: boolean = false;
  @State private appear: number = 0;
  @State private closing: boolean = false;

  private dismiss(): void {
    if (this.closing || this.busy) {
      return;
    }
    this.closing = true;
    animateTo({ duration: 160, curve: Curve.EaseIn, onFinish: () => this.onDismiss() }, () => {
      this.appear = 0;
    });
  }

  aboutToAppear(): void {
    this.range = parseSyncRange(AppPreferences.syncRange());
    this.load();
  }

  private async load(): Promise<void> {
    try {
      const identity = await this.state.prepareSyncIdentity();
      this.code = identity.code;
      this.lastExportedAt = identity.lastExportedAt;
      await this.refreshEstimate();
    } catch (err) {
      this.state.notify((err as Error).message ?? '操作失败');
    }
  }

  private window(): DayWindow {
    const today = todayEpochDay();
    return this.incremental
      ? incrementalWindow(this.range, today, this.lastExportedAt)
      : windowFor(this.range, today);
  }

  private async refreshEstimate(): Promise<void> {
    const window = this.window();
    try {
      const estimate = await this.state.estimateExport(window.from, window.to, this.preserveMoving);
      this.photoCount = estimate.photoCount;
      this.photoBytes = estimate.photoBytes;
    } catch (err) {
      this.photoCount = 0;
      this.photoBytes = 0;
    }
  }

  private windowText(): string {
    const window = this.window();
    let text = `范围：${windowLabel(window, todayEpochDay())} · ${this.photoCount} 张照片`;
    if (this.photoBytes > 0) {
      text += ` · 约 ${formatBytes(this.photoBytes)}`;
    }
    return text;
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#66000000')
        .opacity(this.appear)
        .onClick(() => {
          if (!this.busy) {
            this.dismiss();
          }
        })

      Scroll() {
        Column({ space: 10 }) {
          Text('发送给掌管者')
            .fontSize(22)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.palette.onSurface)
            .width('100%')

          Text('我的身份码')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .margin({ top: 6 })

          Row() {
            Text(this.code.length > 0 ? this.code : '…')
              .fontSize(22)
              .fontFamily('HarmonyOS Sans Mono')
              .fontColor(this.palette.onSurface)
              .layoutWeight(1)
            Text('复制')
              .fontSize(14)
              .fontColor(this.palette.primary)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => copyText(this.code, '身份码已复制'))
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)

          Text('对方首次接收时需要输入这个码。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')

          Text('数据范围')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .margin({ top: 8 })

          Flex({ wrap: FlexWrap.Wrap }) {
            ForEach(syncRangeValues(), (range: SyncRange) => {
              Text(syncRangeLabel(range))
                .fontSize(13)
                .fontColor(this.range === range
                  ? this.palette.onSecondaryContainer
                  : this.palette.onSurfaceVariant)
                .padding({ left: 14, right: 14, top: 6, bottom: 6 })
                .borderRadius(16)
                .borderWidth(1)
                .borderColor(this.range === range ? '#00000000' : this.palette.outline)
                .backgroundColor(this.range === range
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
                  this.range = range;
                  AppPreferences.setSyncRange(range);
                  this.refreshEstimate();
                })
            }, (range: SyncRange) => range)
          }
          .width('100%')

          if (this.lastExportedAt !== null) {
            Row() {
              Column() {
                Text('只发上次发送之后的数据')
                  .fontSize(14)
                  .fontColor(this.palette.onSurface)
                  .width('100%')
                Text('已同步过的记录不重复发送，对方只比对新数据。')
                  .fontSize(12)
                  .fontColor(this.palette.onSurfaceVariant)
                  .width('100%')
              }
              .layoutWeight(1)
              .alignItems(HorizontalAlign.Start)
              Toggle({ type: ToggleType.Switch, isOn: this.incremental })
                .onChange((isOn: boolean) => {
                  this.incremental = isOn;
                  this.refreshEstimate();
                })
            }
            .width('100%')
            .alignItems(VerticalAlign.Center)
          }

          Text(this.windowText())
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')

          Text('照片导出方式')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
          Row({ space: 8 }) {
            Button('保留实况')
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(40)
              .fontColor(this.preserveMoving ? this.palette.onPrimary : this.palette.onSurface)
              .backgroundColor(this.preserveMoving ? this.palette.primary : this.palette.surfaceContainer)
              .onClick(() => { this.preserveMoving = true; this.refreshEstimate(); })
            Button('单帧照片')
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(40)
              .fontColor(!this.preserveMoving ? this.palette.onPrimary : this.palette.onSurface)
              .backgroundColor(!this.preserveMoving ? this.palette.primary : this.palette.surfaceContainer)
              .onClick(() => { this.preserveMoving = false; this.refreshEstimate(); })
          }
          .width('100%')

          if (this.lastExportedAt !== null) {
            Text(`上次发送：${formatMonthDayTime(this.lastExportedAt)}`)
              .fontSize(12)
              .fontColor(this.palette.onSurfaceVariant)
              .width('100%')
          }

          Button(this.busy ? `正在打包 ${this.progress}%` : '打包并发送')
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .width('100%')
            .height(48)
            .fontSize(15)
            .fontColor(this.palette.onPrimary)
            .backgroundColor(this.palette.primary)
            .borderRadius(24)
            .enabled(!this.busy && this.state.currentPerson !== null)
            .margin({ top: 10 })
            .onClick(() => this.send())

          Row() {
            Blank()
            Text('更换验证码')
              .fontSize(13)
              .fontColor(this.palette.error)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => {
                this.confirmChangeCode = true;
              })
          }
          .width('100%')
        }
        .width('100%')
        .padding(24)
        .backgroundColor(this.palette.surface)
        .borderRadius(24)
        .alignItems(HorizontalAlign.Start)
      }
      .constraintSize({ maxWidth: 360, maxHeight: '86%' })
      .margin({ left: 20, right: 20 })
      .scrollBar(BarState.Off)
      .opacity(this.appear)
      .scale({ x: 0.96 + 0.04 * this.appear, y: 0.96 + 0.04 * this.appear })

      if (this.confirmChangeCode) {
        ConfirmDialog({
          palette: this.palette,
          title: '更换验证码？',
          message: '旧验证码立即失效，对方下次接收数据时需要重新验证。',
          confirmText: '更换',
          icon: '↻',
          tone: 'danger',
          onConfirm: () => {
            this.confirmChangeCode = false;
            this.state.regenerateSyncCode().then((identity) => {
              this.code = identity.code;
              this.state.notify('验证码已更换');
            }).catch((err: Error) => {
              this.state.notify(err.message ?? '操作失败');
            });
          },
          onDismiss: () => {
            this.confirmChangeCode = false;
          },
        })
      }
    }
    .width('100%')
    .height('100%')
    .onAppear(() => {
      animateTo({ duration: 260, curve: Curve.FastOutSlowIn }, () => {
        this.appear = 1;
      });
    })
  }

  private async send(): Promise<void> {
    const context = getContext(this) as common.UIAbilityContext;
    this.busy = true;
    this.progress = 0;
    try {
      const result = await SyncService.exportPackage(
        context,
        this.state,
        this.range,
        this.incremental,
        this.preserveMoving,
        (percent: number) => {
          this.progress = percent;
        },
      );
      this.lastExportedAt = Date.now();
      this.busy = false;
      await shareFile(context, result.path, 'locker-sync.zip', 'general.zip-archive');
      this.state.notify(`已生成同步包 · ${result.photoCount} 张照片，范围 ${result.dayCount} 天`);
      this.dismiss();
    } catch (err) {
      this.busy = false;
      this.state.notify((err as Error).message ?? '打包失败');
    }
  }
}

/**
 * 接收弹窗：读清单 → 验证码 → 差异勾选 → 应用。
 * 删除项单独分组、默认不勾选，勾了还要再确认一次。
 */
@Component
export struct ReceiveSyncDialog {
  @Prop palette: Palette;
  @ObjectLink state: AppState;
  /** 由系统分享进来的 zip 路径，为空时让用户手动选文件。 */
  initialZipPath: string = '';
  onDismiss: () => void = () => {
  };

  @State private preview: ImportPreview | null = null;
  @State private diff: ImportDiff | null = null;
  @State private codeInput: string = '';
  @State private codeError: string = '';
  @State private targetNew: boolean = true;
  @State private targetExistingId: number = -1;
  @State private checkedKeys: Set<string> = new Set<string>();
  @State private busy: boolean = false;
  @State private progress: number = 0;
  @State private confirmDelete: boolean = false;
  @State private appear: number = 0;
  @State private closing: boolean = false;

  private dismiss(): void {
    if (this.closing || this.busy) {
      return;
    }
    this.closing = true;
    animateTo({ duration: 160, curve: Curve.EaseIn, onFinish: () => this.onDismiss() }, () => {
      this.appear = 0;
    });
  }

  aboutToAppear(): void {
    if (this.initialZipPath.length > 0) {
      this.loadFrom(this.initialZipPath);
    }
  }

  private async pickFile(): Promise<void> {
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const options = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['.zip'];
      const documentPicker = new picker.DocumentViewPicker(context);
      const uris = await documentPicker.select(options);
      if (uris.length > 0) {
        await this.loadFrom(uris[0]);
      }
    } catch (err) {
      this.state.notify((err as Error).message ?? '选择文件失败');
    }
  }

  private async loadFrom(path: string): Promise<void> {
    const context = getContext(this) as common.UIAbilityContext;
    this.busy = true;
    try {
      // 统一拷到缓存目录：选择器给的 URI 在后续多次读取时不保证可用
      const workDir = FileUtil.join(context.cacheDir, 'sync');
      FileUtil.ensureDir(workDir);
      const local = FileUtil.join(workDir, `incoming-${Date.now()}.zip`);
      FileUtil.writeBytes(local, FileUtil.readBytes(path));
      this.preview = await SyncService.previewImport(context, this.state, local);
      this.codeInput = '';
      this.codeError = '';
      this.targetNew = this.preview.sameNamePersons.length === 0 && this.preview.boundPerson === null;
      this.targetExistingId = this.preview.sameNamePersons.length > 0
        ? -1 : (this.preview.boundPerson?.id ?? -1);
      this.diff = null;
    } catch (err) {
      this.state.notify((err as Error).message ?? '同步包读取失败');
    } finally {
      this.busy = false;
    }
  }

  private async verify(): Promise<void> {
    const preview = this.preview;
    if (preview === null) {
      return;
    }
    const context = getContext(this) as common.UIAbilityContext;
    this.busy = true;
    try {
      if (!this.targetNew && this.targetExistingId <= 0) {
        this.state.notify('请先选择导入目标');
        return;
      }
      let targetId: number | null = null;
      if (!this.targetNew) {
        targetId = this.targetExistingId;
      }
      const diff = await SyncService.verifyAndDiff(
        context,
        this.state,
        preview,
        preview.needsVerification ? this.codeInput : null,
        targetId,
      );
      this.diff = diff;
      this.codeError = '';
      this.checkedKeys = defaultSelectedSyncKeys(diff.changes);
    } catch (err) {
      const message = (err as Error).message ?? '验证失败';
      if (preview.needsVerification && message.indexOf('验证码') >= 0) {
        this.codeError = message;
      } else {
        this.state.notify(message);
      }
    } finally {
      this.busy = false;
    }
  }

  private async apply(): Promise<void> {
    const diff = this.diff;
    if (diff === null) {
      return;
    }
    this.busy = true;
    this.progress = 0;
    try {
      const result = await SyncService.apply(this.state, diff, this.checkedKeys,
        (percent: number) => {
          this.progress = percent;
        });
      const parts: string[] = [];
      if (result.added > 0) {
        parts.push(`新增 ${result.added}`);
      }
      if (result.modified > 0) {
        parts.push(`修改 ${result.modified}`);
      }
      if (result.removed > 0) {
        parts.push(`删除 ${result.removed}`);
      }
      let message = `已同步 · ${diff.targetName}`;
      if (parts.length > 0) {
        message += `（${parts.join('、')}）`;
      }
      if (result.skippedNumberDays.length > 0) {
        message += `；${result.skippedNumberDays.join('、')} 随机数冲突未导入`;
      }
      this.state.notify(message);
      this.busy = false;
      this.dismiss();
    } catch (err) {
      this.state.notify((err as Error).message ?? '同步失败');
    } finally {
      this.busy = false;
    }
  }

  private removalSelectedCount(): number {
    const diff = this.diff;
    if (diff === null) {
      return 0;
    }
    return diff.changes.filter(
      (c: SyncChange) => c.action === SyncAction.REMOVE && this.checkedKeys.has(c.key),
    ).length;
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#66000000')
        .opacity(this.appear)
        .onClick(() => {
          if (!this.busy) {
            this.dismiss();
          }
        })

      Scroll() {
        Column({ space: 10 }) {
          if (this.preview === null) {
            this.PickFileContent()
          } else if (this.diff === null) {
            this.PreviewContent()
          } else {
            this.DiffContent()
          }
        }
        .width('100%')
        .padding(24)
        .backgroundColor(this.palette.surface)
        .borderRadius(24)
        .alignItems(HorizontalAlign.Start)
      }
      .constraintSize({ maxWidth: 380, maxHeight: '86%' })
      .margin({ left: 20, right: 20 })
      .scrollBar(BarState.Off)
      .opacity(this.appear)
      .scale({ x: 0.96 + 0.04 * this.appear, y: 0.96 + 0.04 * this.appear })

      if (this.confirmDelete) {
        ConfirmDialog({
          palette: this.palette,
          title: '将删除本机数据？',
          message: `所选内容包含 ${this.removalSelectedCount()} 条删除操作，` +
            '会从本机移除对应记录，且无法撤销。',
          confirmText: '继续应用',
          icon: '⚠',
          tone: 'danger',
          onConfirm: () => {
            this.confirmDelete = false;
            this.apply();
          },
          onDismiss: () => {
            this.confirmDelete = false;
          },
        })
      }
    }
    .width('100%')
    .height('100%')
    .onAppear(() => {
      animateTo({ duration: 260, curve: Curve.FastOutSlowIn }, () => {
        this.appear = 1;
      });
    })
  }

  @Builder
  PickFileContent() {
    Text('接收掌管者数据')
      .fontSize(22)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.palette.onSurface)
      .width('100%')
    Text('选择对方发来的同步包（zip）。如果文件选择器里找不到，' +
      '可在聊天里长按文件，选择「用其他应用打开」→ Locker。')
      .fontSize(13)
      .fontColor(this.palette.onSurfaceVariant)
      .width('100%')
    Button(this.busy ? '读取中…' : '选择同步包')
      .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
      .width('100%')
      .height(48)
      .fontSize(15)
      .fontColor(this.palette.onPrimary)
      .backgroundColor(this.palette.primary)
      .borderRadius(24)
      .enabled(!this.busy)
      .margin({ top: 8 })
      .onClick(() => this.pickFile())
  }

  @Builder
  PreviewContent() {
    Text('接收掌管者数据')
      .fontSize(22)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.palette.onSurface)
      .width('100%')

    Text(`识别到身份：${this.preview!.manifest.identityName}`)
      .fontSize(15)
      .fontColor(this.palette.onSurface)
      .width('100%')

    if (this.preview!.alreadyImported) {
      Text('这份包与上次成功导入的完全相同，再导入不会有变化。')
        .fontSize(12)
        .fontColor(this.palette.error)
        .width('100%')
    }

    Text(`数据范围：${this.preview!.manifest.fromEpochDay <= 0
      ? '全部历史'
      : windowLabel({
        from: this.preview!.manifest.fromEpochDay,
        to: this.preview!.manifest.toEpochDay,
      }, todayEpochDay())}`)
      .fontSize(12)
      .fontColor(this.palette.onSurfaceVariant)
      .width('100%')

    if (this.preview!.boundPerson !== null && this.preview!.sameNamePersons.length === 0) {
      Text(`已绑定人物「${this.preview!.boundPerson!.name}」，将直接比对差异。`)
        .fontSize(14)
        .fontColor(this.palette.onSurface)
        .width('100%')
    }

    if (this.preview!.needsVerification) {
      if (this.preview!.codeChanged) {
        Text('对方更换了验证码，需要重新验证。')
          .fontSize(12)
          .fontColor(this.palette.error)
          .width('100%')
      }
      TextInput({ placeholder: '验证码', text: this.codeInput })
        .width('100%')
        .height(48)
        .fontSize(15)
        .fontFamily('HarmonyOS Sans Mono')
        .backgroundColor(this.palette.surfaceContainer)
        .borderRadius(12)
        .borderWidth(this.codeError.length > 0 ? 1 : 0)
        .borderColor(this.palette.error)
        .onChange((value: string) => {
          this.codeInput = value;
          this.codeError = '';
        })
      if (this.codeError.length > 0) {
        Text(this.codeError)
          .fontSize(12)
          .fontColor(this.palette.error)
          .width('100%')
      }
    }

    if (this.preview!.sameNamePersons.length > 0) {
      Text('发现同名人物，请选择导入目标：')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
        .margin({ top: 4 })
      if (this.state.currentPerson !== null) {
        Row({ space: 8 }) {
          Radio({ value: `${this.state.currentPerson!.id}`, group: 'importTarget' })
            .checked(!this.targetNew && this.targetExistingId === this.state.currentPerson!.id)
            .enabled(this.preview!.availablePersons.some(
              (p: PersonLike) => p.id === this.state.currentPerson!.id))
            .onChange(() => {
              this.targetNew = false;
              this.targetExistingId = this.state.currentPerson!.id;
            })
          Text(`当前人物「${this.state.currentPerson!.name}」`)
            .fontSize(14)
            .fontColor(this.palette.onSurface)
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      }
      Row({ space: 8 }) {
        Radio({ value: 'new', group: 'importTarget' })
          .checked(this.targetNew)
          .onChange(() => {
            this.targetNew = true;
            this.targetExistingId = -1;
          })
        Text(`新建人物「${this.preview!.manifest.identityName}」`)
          .fontSize(14)
          .fontColor(this.palette.onSurface)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      ForEach(this.preview!.availablePersons.filter(
        (p: PersonLike) => this.state.currentPerson === null || p.id !== this.state.currentPerson!.id),
        (person: PersonLike) => {
        Row({ space: 8 }) {
          Radio({ value: `${person.id}`, group: 'importTarget' })
            .checked(!this.targetNew && this.targetExistingId === person.id)
            .onChange(() => {
              this.targetNew = false;
              this.targetExistingId = person.id;
            })
          Text(`导入现有「${person.name}」${person.name.trim() === this.preview!.manifest.identityName.trim()
            ? '（同名）' : ''}`)
            .fontSize(14)
            .fontColor(this.palette.onSurface)
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      }, (person: PersonLike) => `${person.id}`)
      if (this.preview!.sameNamePersons.some((p: PersonLike) =>
        !this.preview!.availablePersons.some((candidate: PersonLike) => candidate.id === p.id))) {
        Text('部分同名人物已绑定其他同步身份，无法选择。')
          .fontSize(12)
          .fontColor(this.palette.onSurfaceVariant)
          .width('100%')
      }
      if (!this.targetNew && this.targetExistingId > 0) {
        Text('将与所选人物在同步范围内的数据比对，应用时仅修改勾选的差异。')
          .fontSize(12)
          .fontColor(this.palette.onSurfaceVariant)
          .width('100%')
      }
    }

    Row({ space: 12 }) {
      Button('取消')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .layoutWeight(1)
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
        .onClick(() => this.dismiss())
      Button(this.busy ? '正在校验…' : '验证并比对')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .layoutWeight(1)
        .height(46)
        .fontSize(15)
        .fontColor(this.palette.onPrimary)
        .backgroundColor(this.palette.primary)
        .borderRadius(23)
        .enabled(!this.busy && (this.targetNew || this.targetExistingId > 0))
        .onClick(() => this.verify())
    }
    .width('100%')
    .margin({ top: 8 })
  }

  @Builder
  DiffContent() {
    Text('同步预览')
      .fontSize(22)
      .fontWeight(FontWeight.Medium)
      .fontColor(this.palette.onSurface)
      .width('100%')

    Text(this.diff!.changes.length === 0
      ? '没有差异，数据已是最新。'
      : `与「${this.diff!.targetName}」有 ${this.diff!.changes.length} 处不同，勾选要应用的内容。`)
      .fontSize(12)
      .fontColor(this.palette.onSurfaceVariant)
      .width('100%')

    ForEach(this.diff!.changes.filter((c: SyncChange) => c.action !== SyncAction.REMOVE),
      (change: SyncChange) => {
        this.ChangeRow(change, false)
      }, (change: SyncChange) => change.key)

    if (this.diff!.changes.some((c: SyncChange) => c.action === SyncAction.REMOVE)) {
      Divider()
        .color(this.palette.outlineVariant)
        .margin({ top: 8, bottom: 8 })
      Text(`以下 ${this.diff!.changes.filter((c: SyncChange) => c.action === SyncAction.REMOVE).length} 项` +
        '会删除本机数据，默认不勾选')
        .fontSize(12)
        .fontColor(this.palette.error)
        .width('100%')
      ForEach(this.diff!.changes.filter((c: SyncChange) => c.action === SyncAction.REMOVE),
        (change: SyncChange) => {
          this.ChangeRow(change, true)
        }, (change: SyncChange) => change.key)
    }

    Row({ space: 12 }) {
      Button('取消')
        .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
        .layoutWeight(1)
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
        .onClick(() => this.dismiss())
      if (this.diff!.changes.length === 0) {
        Button('关闭')
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .layoutWeight(1)
          .height(46)
          .fontSize(15)
          .fontColor(this.palette.onPrimary)
          .backgroundColor(this.palette.primary)
          .borderRadius(23)
          .onClick(() => this.dismiss())
      } else {
        Button(this.busy
          ? `正在应用 ${this.progress}%`
          : (this.removalSelectedCount() > 0
            ? `应用所选（${this.checkedKeys.size}，删除 ${this.removalSelectedCount()}）`
            : `应用所选（${this.checkedKeys.size}）`))
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .layoutWeight(1)
          .height(46)
          .fontSize(14)
          .fontColor(this.palette.onPrimary)
          .backgroundColor(this.palette.primary)
          .borderRadius(23)
          .enabled(!this.busy && this.checkedKeys.size > 0)
          .onClick(() => {
            if (this.removalSelectedCount() > 0) {
              this.confirmDelete = true;
            } else {
              this.apply();
            }
          })
      }
    }
    .width('100%')
    .margin({ top: 12 })
  }

  @Builder
  ChangeRow(change: SyncChange, isRemoval: boolean) {
    Row({ space: 8 }) {
      Checkbox({ name: change.key })
        .select(this.checkedKeys.has(change.key))
        .onChange((checked: boolean) => {
          const next = new Set<string>(this.checkedKeys);
          if (checked) {
            next.add(change.key);
          } else {
            next.delete(change.key);
          }
          this.checkedKeys = next;
        })
      Column() {
        Text(change.title)
          .fontSize(13)
          .fontColor(isRemoval ? this.palette.error : this.palette.onSurface)
          .width('100%')
        Text(change.detail)
          .fontSize(12)
          .fontColor(isRemoval ? this.palette.error : this.palette.onSurfaceVariant)
          .width('100%')
      }
      .layoutWeight(1)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
  }
}

interface PersonLike {
  id: number;
  name: string;
}
