import { Palette } from '../common/Palette';
import { tapFeedback } from '../common/Haptics';
import { AppState, DailyReportUi, ReportVerification } from './AppState';
import { DailyPhoto, Person } from '../data/Models';
import { todayEpochDay } from '../common/Dates';
import { formatClock } from '../common/TimeFormat';
import { image } from '@kit.ImageKit';
import { common } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { PhotoProcessor, readFileBytes } from '../common/PhotoProcessor';
import { PhotoSource, PickedPhoto, shareFile } from './components/PhotoSource';
import { MovingPhotoView, MovingPhotoViewController, photoAccessHelper } from '@kit.MediaLibraryKit';
import { MovingPhotoLoader } from '../common/MovingPhotoLoader';
import { ConfirmDialog } from './components/Dialogs';
import { generateQrPixelMap, decodeQrFromBytes } from '../common/QrCode';
import { photoCheckLabel, PhotoCheck, DailyReportData } from '../data/DailyReport';
import { ReportCard, ReportCardInput } from '../common/ReportCard';
import { AppPreferences } from '../data/AppPreferences';

/**
 * 打卡页：实时时钟、当天随机数、今日照片、日报二维码、发送今日数据。
 * 与 Android 版对应；二维码生成与识别都是端侧能力，不联网。
 */
@Component
export struct CheckInPage {
  @Prop palette: Palette;
  /** 页签是否正显示本页；由根页面在切换时更新，用于触发数据刷新。 */
  @Prop @Watch('onVisibleChanged') visible: boolean = false;
  @ObjectLink state: AppState;
  onOpenSendDialog: () => void = () => {
  };
  onOpenFullScreenNumber: (number: string) => void = () => {
  };

  @State private now: number = Date.now();
  @State private number: string = '';
  @State private hasPhoto: boolean = false;
  @State private photo: image.PixelMap | undefined = undefined;
  @State private photoBytes: Uint8Array | null = null;
  @State private uploading: boolean = false;
  @State private pendingReplacement: PickedPhoto | null = null;
  @State private movingPhoto: photoAccessHelper.MovingPhoto | null = null;
  private movingController: MovingPhotoViewController = new MovingPhotoViewController();
  @State private reportVisible: boolean = false;
  @State private reportPayload: string = '';
  @State private reportQr: image.PixelMap | undefined = undefined;
  @State private reportData: DailyReportData | null = null;
  @State private reportBusy: boolean = false;
  @State private verification: ReportVerificationUi | null = null;
  @State private scanBusy: boolean = false;
  @State private scanCodeInput: string = '';
  @State private timerId: number = -1;
  /** 随机数卡片按压反馈：按下时轻微缩小。 */
  @State private numberPressed: boolean = false;
  /** 日报弹窗卡片进场进度：0 → 1。 */
  @State private reportAppear: number = 0;
  /** 核验弹窗卡片进场进度：0 → 1。 */
  @State private verifyAppear: number = 0;
  private reportClosing: boolean = false;
  private verifyClosing: boolean = false;

  private dismissReport(): void {
    if (this.reportClosing) {
      return;
    }
    this.reportClosing = true;
    animateTo({ duration: 160, curve: Curve.EaseIn, onFinish: () => {
      this.reportVisible = false;
      this.reportClosing = false;
    } }, () => {
      this.reportAppear = 0;
    });
  }

  private dismissVerification(): void {
    if (this.verifyClosing || this.scanBusy) {
      return;
    }
    this.verifyClosing = true;
    animateTo({ duration: 160, curve: Curve.EaseIn, onFinish: () => {
      this.verification = null;
      this.verifyClosing = false;
    } }, () => {
      this.verifyAppear = 0;
    });
  }

  aboutToAppear(): void {
    if (this.visible) {
      this.startTimer();
    }
    this.reload();
  }

  aboutToDisappear(): void {
    this.stopTimer();
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

  private stopTimer(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  onPersonChanged(): void {
    this.reload();
  }

  /** 页签切回本页时调用：缓存页面不会重新挂载，需要主动刷新一次。 */
  refreshOnShow(): void {
    this.reload();
  }

  /** 页签切到本页时刷新：Swiper 缓存页面不会重新挂载。 */
  onVisibleChanged(): void {
    if (this.visible) {
      this.startTimer();
      this.refreshOnShow();
    } else {
      this.stopTimer();
    }
  }

  private async reload(): Promise<void> {
    const person: Person | null = this.state.currentPerson;
    if (person === null) {
      this.number = '';
      this.hasPhoto = false;
      this.photo = undefined;
      this.photoBytes = null;
      this.movingPhoto = null;
      return;
    }
    const epochDay: number = todayEpochDay();
    const number: string | null = await this.state.ensureDailyRandomNumber(epochDay);
    this.number = number ?? '';
    const photo: DailyPhoto | null = await this.state.getDailyPhoto(person.id, epochDay);
    if (photo === null) {
      this.hasPhoto = false;
      this.photo = undefined;
      this.photoBytes = null;
      this.movingPhoto = null;
    } else {
      this.hasPhoto = true;
      this.photoBytes = photo.jpeg;
      let decoded = await PhotoProcessor.decodeScaled(photo.jpeg, 720);
      if (decoded === null) {
        decoded = await PhotoProcessor.decodeScaled(photo.jpeg, 160);
      }
      this.photo = decoded ?? undefined;
      this.movingPhoto = null;
      if (photo.videoFingerprint.length > 0) {
        try {
          photo.video = await this.state.getDailyPhotoVideo(person.id, epochDay);
          if (photo.video !== null) {
            this.movingPhoto = await MovingPhotoLoader.load(getContext(this) as common.UIAbilityContext, photo);
          }
        } catch (err) {
          this.movingPhoto = null;
        }
      }
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Scroll() {
        Column({ space: 0 }) {
          Text('打卡')
            .fontSize(28)
            .fontFamily('HarmonyOS Sans')
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onSurface)
            .width('100%')
            .margin({ bottom: 8 })

          Text('当前时间')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
          Text(formatClock(this.now))
            .fontSize(16)
            .fontFamily('HarmonyOS Sans Mono')
            .fontColor(this.palette.onSurface)
            .width('100%')
            .margin({ bottom: 8 })

          Text(`当前人物：${this.state.currentPerson?.name ?? '—'}`)
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .margin({ bottom: 20 })

          // 随机数卡片
          Column() {
            Text('5 位随机数')
              .fontSize(12)
              .fontColor(this.palette.onSurfaceVariant)
            Text(this.number.length > 0 ? this.number : '—')
              .fontSize(56)
              .fontWeight(FontWeight.Bold)
              .fontFamily('HarmonyOS Sans Mono')
              .letterSpacing(6)
              .fontColor(this.palette.onSurface)
              .margin({ top: 12 })
              .transition(TransitionEffect.OPACITY.animation({ duration: 250 }))
            if (this.number.length > 0) {
              Text('点击全屏横屏显示 · 便于拍照核验')
                .fontSize(12)
                .fontColor(this.palette.onSurfaceVariant)
                .margin({ top: 10 })
            }
          }
          .width('100%')
          .padding({ top: 40, bottom: 40, left: 20, right: 20 })
          .backgroundColor(this.palette.surface)
          .borderRadius(20)
          .alignItems(HorizontalAlign.Center)
          // 按压反馈：按下缩到 0.97，松开或取消时回弹
          .scale({ x: this.numberPressed ? 0.97 : 1, y: this.numberPressed ? 0.97 : 1 })
          .onTouch((event: TouchEvent) => {
            if (event.type === TouchType.Down) {
              animateTo({ duration: 120 }, () => {
                this.numberPressed = true;
              });
            } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
              animateTo({ duration: 120 }, () => {
                this.numberPressed = false;
              });
            }
          })
          .onClick(() => {
            if (this.number.length > 0) {
              this.onOpenFullScreenNumber(this.number);
            }
          })

          Row({ space: 12 }) {
            Button(this.reportBusy ? '核验照片中…' : '今日日报')
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(48)
              .fontSize(14)
              .fontColor(this.palette.onPrimary)
              .backgroundColor(this.palette.primary)
              .borderRadius(24)
              .enabled(this.state.currentPerson !== null && !this.reportBusy)
              .onClick(() => this.openReport())

            Button(this.scanBusy ? '核验中…' : '核验二维码')
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(48)
              .fontSize(14)
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
              .enabled(!this.scanBusy)
              .onClick(() => this.scanReport())
          }
          .width('100%')
          .margin({ top: 16 })

          Button('发送今日数据')
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .width('100%')
            .height(48)
            .fontSize(14)
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
            .margin({ top: 12 })
            .enabled(this.state.currentPerson !== null)
            .onClick(() => this.onOpenSendDialog())

          Text('仅包含今天的分段、事件、随机数和照片；对方第一次接收需要身份码。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .margin({ top: 6, bottom: 32 })

          Text('今日照片')
            .fontSize(18)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onSurface)
            .width('100%')
            .margin({ bottom: 8 })

          if (this.photo !== undefined) {
            if (this.movingPhoto !== null) {
              MovingPhotoView({ movingPhoto: this.movingPhoto, controller: this.movingController })
                .width('100%')
                .height(220)
                .objectFit(ImageFit.Cover)
                .borderRadius(16)
                .margin({ bottom: 12 })
                .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
                .onClick(() => this.movingController.startPlayback())
            } else {
              Image(this.photo)
                .width('100%')
                .height(220)
                .objectFit(ImageFit.Cover)
                .borderRadius(16)
                .margin({ bottom: 12 })
                .transition(TransitionEffect.OPACITY.animation({ duration: 250 }))
            }
          } else {
            Row({ space: 10 }) {
              Row() {
                Text('i')
                  .fontSize(12)
                  .fontWeight(FontWeight.Bold)
                  .fontColor(this.palette.onSurfaceVariant)
              }
              .width(28)
              .height(28)
              .borderRadius(14)
              .backgroundColor(this.palette.surfaceContainerHighest)
              .justifyContent(FlexAlign.Center)

              Text(this.hasPhoto ? '照片已上传，预览暂不可用' : '今天还没有上传照片')
                .fontSize(14)
                .fontWeight(FontWeight.Medium)
                .fontColor(this.palette.onSurfaceVariant)
                .layoutWeight(1)
            }
            .width('100%')
            .padding({ left: 14, right: 14, top: 12, bottom: 12 })
            .borderRadius(16)
            .backgroundColor(this.palette.surfaceContainerLow)
            .alignItems(VerticalAlign.Center)
          }

          Button(this.hasPhoto ? '更换今日照片' : '上传今日照片')
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .width('100%')
            .height(52)
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onPrimary)
            .backgroundColor(this.palette.primary)
            .borderRadius(26)
            .margin({ top: 12 })
            .enabled(this.state.currentPerson !== null && !this.uploading)
            .onClick(() => this.uploadPhoto())

          Text('只能上传今天及之前的照片；上传后会显示在月历今天的格子里。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .margin({ top: 12 })
        }
        .width('100%')
        .padding({ left: 24, right: 24, top: 24, bottom: 120 })
        .alignItems(HorizontalAlign.Center)
      }
      .scrollBar(BarState.Off)
      .width('100%')
      .height('100%')
      .backgroundColor(this.palette.background)

      if (this.pendingReplacement !== null) {
        ConfirmDialog({
          palette: this.palette,
          title: '更换今日照片？',
          message: '今天已经有一张照片，确定用新照片替换吗？',
          confirmText: '替换',
          icon: '↻',
          tone: 'primary',
          onConfirm: () => {
            const picked: PickedPhoto | null = this.pendingReplacement;
            this.pendingReplacement = null;
            if (picked !== null) {
              this.state.setDailyPhoto(todayEpochDay(), picked.jpeg, picked.video).then(() => this.reload());
            }
          },
          onDismiss: () => {
            this.pendingReplacement = null;
          },
        })
      }

      if (this.reportVisible && this.reportQr !== undefined && this.reportData !== null) {
        this.ReportDialog()
      }

      if (this.verification !== null) {
        this.VerificationDialog()
      }
    }
  }

  @Builder
  ReportDialog() {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#66000000')
        .opacity(this.reportAppear)
        .onClick(() => {
          this.dismissReport();
        })

      Scroll() {
        Column({ space: 8 }) {
          Text('今日日报')
            .fontSize(20)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.palette.onSurface)
          Text(`${this.reportData!.number} · 佩戴 ${this.reportData!.wearMinutes} 分钟 · ` +
            `事件 ${this.reportData!.eventCount} 次`)
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)

          // 二维码不要圆角裁切：会切掉静默区，导致对方截图后识别失败
          Image(this.reportQr)
            .width(260)
            .height(260)
            .backgroundColor(Color.White)
            .interpolation(ImageInterpolation.High)

          Text(photoCheckLabel(this.reportData!.photoCheck))
            .fontSize(12)
            .fontColor(this.reportData!.photoCheck === PhotoCheck.PASS
              ? this.palette.primary
              : (this.reportData!.photoCheck === PhotoCheck.MISMATCH
                ? this.palette.error
                : this.palette.onSurfaceVariant))

          Text('对方在 Locker 里「核验二维码」即可确认真伪。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)

          Row({ space: 12 }) {
            Button('关闭')
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(44)
              .fontSize(14)
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
              .onClick(() => {
                this.dismissReport();
              })
            Button('分享日报卡')
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(44)
              .fontSize(14)
              .fontColor(this.palette.onPrimary)
              .backgroundColor(this.palette.primary)
              .borderRadius(22)
              .onClick(() => this.shareReportCard())
          }
          .width('100%')
          .margin({ top: 8 })
        }
        .width('100%')
        .padding(24)
        .backgroundColor(this.palette.surface)
        .borderRadius(24)
        .alignItems(HorizontalAlign.Center)
        .opacity(this.reportAppear)
        .scale({ x: 0.9 + 0.1 * this.reportAppear, y: 0.9 + 0.1 * this.reportAppear })
        // 等卡片挂载后再启动动画：新建节点没有旧值，直接在打开回调里 animateTo 不会生效
        .onAppear(() => {
          animateTo({ duration: 250, curve: Curve.EaseOut }, () => {
            this.reportAppear = 1;
          });
        })
      }
      .constraintSize({ maxHeight: '86%' })
      .scrollBar(BarState.Off)
      .margin({ left: 20, right: 20 })
    }
    .width('100%')
    .height('100%')
    .alignContent(Alignment.Center)
  }

  @Builder
  VerificationDialog() {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#66000000')
        .opacity(this.verifyAppear)
        .onClick(() => {
          this.dismissVerification();
        })

      Column({ space: 6 }) {
        Text('核验结果')
          .fontSize(20)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.palette.onSurface)
          .width('100%')

        Text(`身份：${this.verification!.identityName ?? this.verification!.report.identityId.substring(0, 8) + '…'}`)
          .fontSize(14)
          .fontColor(this.palette.onSurface)
          .width('100%')
        Text(`随机数：${this.verification!.report.number}`)
          .fontSize(14)
          .fontFamily('HarmonyOS Sans Mono')
          .fontColor(this.palette.onSurface)
          .width('100%')
        Text(`佩戴：${this.verification!.report.wearMinutes} 分钟 · ` +
          `事件 ${this.verification!.report.eventCount} 次`)
          .fontSize(14)
          .fontColor(this.palette.onSurface)
          .width('100%')

        if (this.verification!.needsCode) {
          Text('首次核验需要对方告诉你的身份码。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .margin({ top: 8 })
          TextInput({ placeholder: '验证码', text: this.scanCodeInput })
            .width('100%')
            .height(44)
            .fontSize(15)
            .fontFamily('HarmonyOS Sans Mono')
            .backgroundColor(this.palette.surfaceContainer)
            .borderRadius(12)
            .onChange((value: string) => {
              this.scanCodeInput = value;
            })
          Button(this.scanBusy ? '验证中…' : '验证')
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .width('100%')
            .height(44)
            .fontSize(15)
            .fontColor(this.palette.onPrimary)
            .backgroundColor(this.palette.primary)
            .borderRadius(22)
            .enabled(!this.scanBusy)
            .margin({ top: 8 })
            .onClick(() => this.verifyWithCode())
        } else {
          Text(this.verification!.signatureValid
            ? '✓ 签名有效，日报可信'
            : '✗ 签名不匹配：可能被篡改，或验证码错误')
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .fontColor(this.verification!.signatureValid
              ? this.palette.primary
              : this.palette.error)
            .width('100%')
            .margin({ top: 8 })

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
            .margin({ top: 12 })
            .onClick(() => {
              this.dismissVerification();
            })
        }
      }
      .width('86%')
      .padding(24)
      .backgroundColor(this.palette.surface)
      .borderRadius(24)
      .alignItems(HorizontalAlign.Start)
      .opacity(this.verifyAppear)
      .scale({ x: 0.9 + 0.1 * this.verifyAppear, y: 0.9 + 0.1 * this.verifyAppear })
      // 等卡片挂载后再启动动画：新建节点没有旧值，直接在打开回调里 animateTo 不会生效
      .onAppear(() => {
        animateTo({ duration: 250, curve: Curve.EaseOut }, () => {
          this.verifyAppear = 1;
        });
      })
    }
    .width('100%')
    .height('100%')
    .alignContent(Alignment.Center)
  }

  private async openReport(): Promise<void> {
    this.reportBusy = true;
    try {
      const report: DailyReportUi = await this.state.buildDailyReport();
      this.reportData = report.report;
      this.reportPayload = report.payload;
      this.reportQr = (await generateQrPixelMap(report.payload, 1024)) ?? undefined;
      // 开场值归零，卡片挂载后由 onAppear 动画到 1
      this.reportClosing = false;
      this.reportAppear = 0;
      this.reportVisible = true;
    } catch (err) {
      this.state.notify((err as Error).message ?? '无法生成日报');
    } finally {
      this.reportBusy = false;
    }
  }

  private async scanReport(): Promise<void> {
    const context = getContext(this) as common.UIAbilityContext;
    this.scanBusy = true;
    try {
      const uri: string | null = await PhotoSource.pickFromGallery();
      if (uri === null) {
        return;
      }
      const bytes: Uint8Array | null = readFileBytes(uri);
      const payload: string | null = bytes === null
        ? null
        : await decodeQrFromBytes(bytes, context.cacheDir);
      if (payload === null) {
        this.state.notify('没能识别出二维码。请让对方原样截图，并保证整张二维码完整、清晰可见。');
        return;
      }
      const verification: ReportVerification = await this.state.verifyDailyReport(payload, null);
      const ui: ReportVerificationUi = {
        identityName: verification.identityName,
        report: verification.report,
        signatureValid: verification.signatureValid,
        needsCode: verification.needsCode,
        payload,
      };
      // 开场值归零，卡片挂载后由 onAppear 动画到 1
      this.verifyClosing = false;
      this.verifyAppear = 0;
      this.verification = ui;
    } catch (err) {
      this.state.notify((err as Error).message ?? '核验失败');
    } finally {
      this.scanBusy = false;
    }
  }

  private async verifyWithCode(): Promise<void> {
    const payload: string | undefined = this.verification?.payload;
    if (payload === undefined) {
      return;
    }
    this.scanBusy = true;
    try {
      const verification: ReportVerification = await this.state.verifyDailyReport(payload, this.scanCodeInput);
      const ui: ReportVerificationUi = {
        identityName: verification.identityName,
        report: verification.report,
        signatureValid: verification.signatureValid,
        needsCode: verification.needsCode,
        payload,
      };
      this.verification = ui;
    } catch (err) {
      this.state.notify((err as Error).message ?? '验证失败');
    } finally {
      this.scanBusy = false;
    }
  }

  private async shareReportCard(): Promise<void> {
    if (this.reportData === null) {
      return;
    }
    const context = getContext(this) as common.UIAbilityContext;
    try {
      const input: ReportCardInput = {
        identityName: this.state.currentPerson?.name ?? '',
        epochDay: this.reportData.epochDay,
        number: this.reportData.number,
        wearMinutes: this.reportData.wearMinutes,
        eventCount: this.reportData.eventCount,
        photoLabel: photoCheckLabel(this.reportData.photoCheck),
        photoPassed: this.reportData.photoCheck === PhotoCheck.PASS,
        photoBytes: this.photoBytes,
        qrPayload: this.reportPayload,
      };
      const card: string | null = await ReportCard.render(input, context.cacheDir);
      if (card === null) {
        this.state.notify('生成分享卡失败');
        return;
      }
      await shareFile(context, card, 'locker-report.png', 'general.image');
    } catch (err) {
      this.state.notify((err as Error).message ?? '分享失败');
    }
  }

  private uploadPhoto(): void {
    const context = getContext(this) as common.UIAbilityContext;
    this.uploading = true;
    PhotoSource.pick(context, context.cacheDir).then((picked: PickedPhoto | null) => {
      this.uploading = false;
      if (picked === null) {
        return;
      }
      if (this.hasPhoto) {
        this.pendingReplacement = picked;
      } else {
        this.state.setDailyPhoto(todayEpochDay(), picked.jpeg, picked.video).then(() => this.reload());
      }
    }).catch((err: Error) => {
      this.uploading = false;
      this.state.notify(err.message ?? '上传照片失败');
    });
  }
}

/**
 * 全屏横屏显示随机数：方便用另一台设备拍摄。
 * 显示期间强制横屏、保持屏幕常亮并隐藏系统栏；点击任意处返回。
 * 提供镜像开关，前置摄像头拍摄时打开后数字方向才正确。
 */
@Component
export struct FullScreenNumber {
  @Prop number: string;
  @Prop @Watch('onCloseRequested') closeRequested: boolean = false;
  onDismiss: () => void = () => {
  };
  @State private mirrored: boolean = false;
  /** 全屏页进场进度：0 → 1。 */
  @State private appear: number = 0;
  private closing: boolean = false;
  @State private numberAreaWidth: number = 0;
  @State private numberAreaHeight: number = 0;
  private modeTask: Promise<void> | null = null;
  private previousOrientation: window.Orientation = window.Orientation.UNSPECIFIED;
  private previousKeepScreenOn: boolean = false;
  private previousLayoutFullScreen: boolean = false;
  private fullScreenWindow: window.Window | null = null;

  aboutToAppear(): void {
    this.mirrored = AppPreferences.fullScreenMirrored();
    this.modeTask = this.enterFullScreenMode();
  }

  aboutToDisappear(): void {
    this.exitFullScreenMode().catch(() => {
    });
  }

  private onCloseRequested(): void {
    if (this.closeRequested) {
      this.dismiss();
    }
  }

  private dismiss(): void {
    if (this.closing) {
      return;
    }
    this.closing = true;
    this.getUIContext().animateTo({ duration: 160, curve: Curve.EaseIn, onFinish: () => this.onDismiss() }, () => {
      this.appear = 0;
    });
  }

  /** 进入全屏展示：强制横屏、屏幕常亮、隐藏系统栏；部分设备不支持时忽略失败。 */
  private async enterFullScreenMode(): Promise<void> {
    try {
      const win: window.Window = await window.getLastWindow(getContext(this) as common.UIAbilityContext);
      this.previousOrientation = win.getPreferredOrientation();
      const properties = win.getWindowProperties();
      this.previousKeepScreenOn = properties.isKeepScreenOn;
      this.previousLayoutFullScreen = properties.isLayoutFullScreen;
      this.fullScreenWindow = win;
      await win.setPreferredOrientation(window.Orientation.LANDSCAPE);
      await win.setWindowKeepScreenOn(true);
      await win.setWindowLayoutFullScreen(true);
      // 空数组表示状态栏与导航栏都隐藏
      const none: Array<'status' | 'navigation'> = [];
      await win.setWindowSystemBarEnable(none);
      await win.setSpecificSystemBarEnabled('navigationIndicator', false);
    } catch (err) {
      // 部分设备或窗口不支持这些设置，失败时保持系统默认行为
    }
  }

  /** 退出全屏展示：方向恢复跟随系统、允许自动熄屏、重新显示系统栏。 */
  private async exitFullScreenMode(): Promise<void> {
    try {
      // 等进入操作结束后再恢复，避免快速返回时异步设置把系统栏重新隐藏。
      if (this.modeTask !== null) {
        await this.modeTask;
      }
      const win = this.fullScreenWindow;
      if (win === null) {
        return;
      }
      await win.setPreferredOrientation(this.previousOrientation);
      await win.setWindowKeepScreenOn(this.previousKeepScreenOn);
      await win.setWindowLayoutFullScreen(this.previousLayoutFullScreen);
      const bars: Array<'status' | 'navigation'> = ['status', 'navigation'];
      await win.setWindowSystemBarEnable(bars);
      await win.setSpecificSystemBarEnabled('navigationIndicator', true);
    } catch (err) {
      // 恢复失败不影响返回流程
    }
  }

  private numberFontSize(): number {
    if (this.numberAreaWidth <= 0 || this.numberAreaHeight <= 0) {
      return 72;
    }
    const width = Math.max(1, this.numberAreaWidth - 64);
    const height = Math.max(1, this.numberAreaHeight - 24);
    return Math.max(32, Math.floor(Math.min(width / (Math.max(1, this.number.length) * 0.72), height / 1.3)));
  }

  build() {
    Column() {
      Column() {
        Text(this.number)
          .fontSize(this.numberFontSize())
          .minFontSize(32)
          .maxFontSize(this.numberFontSize())
          .maxLines(1)
          .heightAdaptivePolicy(TextHeightAdaptivePolicy.LAYOUT_CONSTRAINT_FIRST)
          .textAlign(TextAlign.Center)
          .width('100%')
          .layoutWeight(1)
          .fontWeight(FontWeight.Bold)
          .fontFamily('HarmonyOS Sans Mono')
          .letterSpacing(0)
          .fontColor(Color.White)
          // 镜像与进场缩放必须合并成一次 scale：重复调用 scale 会覆盖前一次
          .scale({
            x: this.mirrored ? -(0.92 + 0.08 * this.appear) : (0.92 + 0.08 * this.appear),
            y: 0.92 + 0.08 * this.appear
          })
      }
      .width('100%')
      .layoutWeight(1)
      .padding({ left: 32, right: 32, top: 16, bottom: 8 })
      .alignItems(HorizontalAlign.Center)
      .opacity(this.appear)
      .onAreaChange((oldValue: Area, newValue: Area) => {
        this.numberAreaWidth = Number(newValue.width);
        this.numberAreaHeight = Number(newValue.height);
      })
      .onClick(() => this.dismiss())

      Row() {
        Button('关闭')
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .height(44)
          .fontSize(14)
          .fontColor(Color.White)
          .backgroundColor(Color.Transparent)
          .backgroundEffect({ radius: 14, saturation: 1, color: '#A82A2A2A' })
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
          .onClick(() => this.dismiss())
        Blank()
        Row({ space: 10 }) {
          Text('镜像')
            .fontSize(14)
            .fontColor(Color.White)
          Toggle({ type: ToggleType.Switch, isOn: this.mirrored })
            .onChange((isOn: boolean) => {
              this.mirrored = isOn;
              AppPreferences.setFullScreenMirrored(isOn);
            })
        }
        .height(44)
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .height(72)
      .padding({ left: 32, right: 32, bottom: 16 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(Color.Black)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    .onAppear(() => {
      this.getUIContext().animateTo({ duration: 220, curve: Curve.EaseOut }, () => {
        this.appear = 1;
      });
    })
  }
}

export interface ReportVerificationUi {
  identityName: string | null;
  report: DailyReportData;
  signatureValid: boolean;
  needsCode: boolean;
  payload: string;
}
