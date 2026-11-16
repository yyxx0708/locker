import { curves } from '@kit.ArkUI';
import { Palette } from '../../common/Palette';
import { LockGlyph } from './LockGlyph';
import { tapFeedback } from '../../common/Haptics';

/**
 * 二次确认弹窗。
 *
 * 做成自包含组件而不是「外壳 + 内容闭包」：ArkTS 的 @Builder 函数不能像
 * Compose 那样接收尾随闭包，@BuilderParam 又只能在对象字面量里传静态 builder。
 * 每个弹窗自带遮罩与卡片，虽然多写几行，但调用点最干净、也不会踩编译限制。
 */
@Component
export struct ConfirmDialog {
  @Prop palette: Palette;
  @Prop reduceMotion: boolean = false;
  title: string = '';
  message: string = '';
  confirmText: string = '确定';
  /** 空串表示不显示图标徽章。 */
  icon: string = '';
  /** 图标类型：'lock' / 'unlock' 时用会动的挂锁，其余走 icon 文本。 */
  iconKind: string = '';
  /** primary / neutral / danger */
  tone: string = 'primary';
  onConfirm: () => void = () => {
  };
  onDismiss: () => void = () => {
  };

  /** 入场进度：0 → 1，驱动遮罩渐显与卡片缩放。 */
  @State private appear: number = 0;
  /** 正在播放退场动画：期间忽略重复点击，避免连点触发两次确认。 */
  @State private closing: boolean = false;

  /**
   * 收起弹窗：先把卡片缩回并淡出，动画结束后再回调 onDismiss。
   * [then] 用于确认类操作——先播完退场动画，再执行真正的动作。
   * 使用 onFinish 回调替代 setTimeout，避免动画时序脆弱。
   */
  private dismiss(then: (() => void) | null = null): void {
    if (this.closing) {
      return;
    }
    this.closing = true;
    if (this.reduceMotion) {
      this.appear = 0;
      if (then !== null) {
        then();
      }
      this.onDismiss();
      return;
    }
    animateTo({
      duration: 160,
      curve: Curve.EaseIn,
      onFinish: () => {
        if (then !== null) {
          then();
        }
        this.onDismiss();
      }
    }, () => {
      this.appear = 0;
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
        Column() {
          if (this.iconKind === 'lock' || this.iconKind === 'unlock') {
            LockGlyph({
              open: this.iconKind === 'unlock',
              reduceMotion: this.reduceMotion,
              tint: this.iconColor(),
              closedColor: this.palette.primaryContainer,
              openColor: this.palette.surface,
            })
              .margin({ bottom: 18 })
          } else if (this.icon.length > 0) {
            Row() {
              Text(this.icon)
                .fontSize(28)
                .fontColor(this.iconColor())
            }
            .width(64)
            .height(64)
            .borderRadius(32)
            .backgroundColor(this.badgeColor())
            .justifyContent(FlexAlign.Center)
            .margin({ bottom: 18 })
          }

          Text(this.title)
            .fontSize(24)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.palette.onSurface)
            .textAlign(TextAlign.Center)
            .margin({ bottom: 8 })

          Text(this.message)
            .fontSize(14)
            .fontColor(this.palette.onSurfaceVariant)
            .textAlign(TextAlign.Center)
            .margin({ bottom: 24 })

          Row({ space: 12 }) {
            Button('取消')
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

            Button(this.confirmText)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(48)
              .fontSize(15)
              .fontColor(this.buttonTextColor())
              .backgroundColor(this.buttonColor())
              .borderRadius(24)
              .onClick(() => {
                tapFeedback();
                this.dismiss(() => this.onConfirm());
              })
          }
          .width('100%')
        }
        .width('100%')
        .padding({ left: 24, right: 24, top: 28, bottom: 24 })
        .backgroundColor(this.palette.surface)
        .borderRadius(28)
        .scale({ x: 0.96 + 0.04 * this.appear, y: 0.96 + 0.04 * this.appear })
        .opacity(this.appear)
      }
      .constraintSize({ maxWidth: 360, maxHeight: '86%' })
      .margin({ left: 24, right: 24 })
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .onAppear(() => {
      // 等节点挂载后再动画：首帧已经有 appear=0 的旧值，动画才看得出效果
      if (this.reduceMotion) {
        this.appear = 1;
      } else {
        animateTo({ duration: 320, curve: Curve.FastOutSlowIn }, () => {
          this.appear = 1;
        });
      }
    })
  }

  private badgeColor(): string {
    if (this.tone === 'danger') {
      return this.palette.errorContainer;
    }
    if (this.tone === 'neutral') {
      return this.palette.surfaceContainerHigh;
    }
    return this.palette.primaryContainer;
  }

  private iconColor(): string {
    if (this.tone === 'danger') {
      return this.palette.error;
    }
    if (this.tone === 'neutral') {
      return this.palette.onSurface;
    }
    return this.palette.primary;
  }

  private buttonColor(): string {
    if (this.tone === 'danger') {
      return this.palette.error;
    }
    if (this.tone === 'neutral') {
      return this.palette.secondary;
    }
    return this.palette.primary;
  }

  private buttonTextColor(): string {
    if (this.tone === 'danger') {
      return this.palette.onError;
    }
    if (this.tone === 'neutral') {
      return this.palette.onSecondary;
    }
    return this.palette.onPrimary;
  }
}

/**
 * 表单弹窗：标题 + 可滚动内容 + 取消/保存。
 *
 * 内容用 ArkUI 的尾随闭包注入（组件只有一个 @BuilderParam 时可用）：
 *   FormDialog({ palette, title, confirmEnabled, onConfirm, onDismiss }) {
 *     Column() { ... }
 *   }
 * 需要「删除」时传 onDelete，标题右侧会自动出现红色按钮。
 */
@Component
export struct FormDialog {
  @Prop palette: Palette;
  title: string = '';
  @Prop confirmEnabled: boolean = true;
  confirmText: string = '保存';
  onConfirm: () => void = () => {
  };
  onDismiss: () => void = () => {
  };
  /** 不为 null 时标题右侧显示「删除」。 */
  onDelete: (() => void) | null = null;
  @BuilderParam content: () => void;

  /** 入场进度：0 → 1，驱动遮罩渐显与卡片缩放。 */
  @State private appear: number = 0;
  /** 正在播放退场动画：期间忽略重复点击。 */
  @State private closing: boolean = false;

  /** 收起弹窗：先缩回淡出，再回调 onDismiss。 */
  private dismiss(then: (() => void) | null = null): void {
    if (this.closing) {
      return;
    }
    this.closing = true;
    animateTo({
      duration: 160,
      curve: Curve.EaseIn,
      onFinish: () => {
        if (then !== null) {
          then();
        }
        this.onDismiss();
      }
    }, () => {
      this.appear = 0;
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
        Column() {
          Row() {
            Text(this.title)
              .fontSize(24)
              .fontWeight(FontWeight.Bold)
              .fontColor(this.palette.onSurface)
              .layoutWeight(1)
            if (this.onDelete !== null) {
              Text('删除')
                .fontSize(14)
                .fontColor(this.palette.error)
                .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
                .onClick(() => {
                  if (this.onDelete !== null) {
                    this.onDelete();
                  }
                })
            }
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ bottom: 16 })

          Column() {
            this.content()
          }
          .width('100%')
          .margin({ bottom: 20 })
          .opacity(this.appear)
          .scale({ x: 0.95 + 0.05 * this.appear, y: 0.95 + 0.05 * this.appear })

          Row({ space: 12 }) {
            Button('取消')
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

            Button(this.confirmText)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .layoutWeight(1)
              .height(48)
              .fontSize(15)
              .fontColor(this.palette.onPrimary)
              .backgroundColor(this.confirmEnabled ? this.palette.primary : this.palette.outline)
              .borderRadius(24)
              .enabled(this.confirmEnabled)
              .onClick(() => {
                if (this.confirmEnabled) {
                  tapFeedback();
                  this.dismiss(() => this.onConfirm());
                }
              })
          }
          .width('100%')
        }
        .width('100%')
        .padding({ left: 24, right: 24, top: 24, bottom: 16 })
        .backgroundColor(this.palette.surface)
        .borderRadius(28)
        .scale({ x: 0.86 + 0.14 * this.appear, y: 0.86 + 0.14 * this.appear })
        .opacity(this.appear)
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
