import { curves } from '@kit.ArkUI';

/**
 * 确认弹窗的挂锁徽章，锁形按 24 格网格绘制。
 *
 * 与 Android 版的 LockGlyph 对应：锁扣是一个从左侧立起、绕过顶部、到右侧落下的
 * 圆头路径，锁体是圆角矩形，中间一个钥匙孔。
 *
 * 开锁时锁扣向上抬 2 格并向左甩开 10°，用弹性曲线收尾；
 * 弹窗出现后稍等片刻再动，让用户先看清「要上锁 / 要开锁」的初始状态。
 */
@Component
export struct LockGlyph {
  /** true 表示开锁（锁扣弹开），false 表示上锁（锁扣扣下）。 */
  @Prop open: boolean;
  @Prop reduceMotion: boolean = false;
  /** 锁体与锁扣的颜色。 */
  @Prop tint: string;
  @Prop closedColor: string;
  @Prop openColor: string;

  /** 0 = 锁扣扣下，1 = 锁扣弹开。 */
  @State private fraction: number = 0;
  @State private badgeColor: string = '';
  private animationTimer: number = -1;

  aboutToAppear(): void {
    // 上锁从打开开始，开锁从闭合开始。
    if (this.reduceMotion) {
      this.fraction = this.open ? 1 : 0;
      this.badgeColor = this.open ? this.openColor : this.closedColor;
      return;
    }
    this.fraction = this.open ? 0 : 1;
    this.badgeColor = this.open ? this.closedColor : this.openColor;
    this.animationTimer = setTimeout(() => {
      this.animationTimer = -1;
      this.getUIContext().animateTo(
        { curve: curves.springMotion(0.36, 0.65) },
        () => {
          this.fraction = this.open ? 1 : 0;
          this.badgeColor = this.open ? this.openColor : this.closedColor;
        },
      );
    }, 240);
  }

  aboutToDisappear(): void {
    if (this.animationTimer >= 0) {
      clearTimeout(this.animationTimer);
      this.animationTimer = -1;
    }
  }

  private shacklePath(): string {
    // Path 的命令坐标使用 px，布局尺寸使用 vp。
    const unit = this.getUIContext().vp2px(1);
    return `M${8.3 * unit} ${11.7 * unit} L${8.3 * unit} ${8.4 * unit} ` +
      `A${3.7 * unit} ${3.7 * unit} 0 0 1 ${15.7 * unit} ${8.4 * unit} ` +
      `L${15.7 * unit} ${11.7 * unit}`;
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Row()
        .width(64)
        .height(64)
        .borderRadius(32)
        .backgroundColor(this.badgeColor.length > 0
          ? this.badgeColor
          : (this.open ? this.closedColor : this.openColor))

      Stack({ alignContent: Alignment.TopStart }) {
        // 锁扣：左竖线 → 顶部半圆 → 右竖线
        Path({
          width: 24,
          height: 24,
          commands: this.shacklePath(),
        })
          .fillOpacity(0)
          .stroke(this.tint)
          .strokeWidth(2.3)
          .strokeLineCap(LineCapStyle.Round)
          .strokeLineJoin(LineJoinStyle.Round)
          .translate({ y: -2 * this.fraction })
          .rotate({
            angle: -10 * this.fraction,
            centerX: 8.3,
            centerY: 10.75,
          })

        Column()
          .width(14.5)
          .height(10.25)
          .borderRadius(3.25)
          .backgroundColor(this.tint)
          .position({ x: 4.75, y: 10.75 })

        Column()
          .width(2.9)
          .height(2.9)
          .borderRadius(1.45)
          .backgroundColor(this.badgeColor.length > 0
            ? this.badgeColor
            : (this.open ? this.closedColor : this.openColor))
          .position({ x: 10.55, y: 14.45 })
      }
      .width(24)
      .height(24)
      .scale({ x: 1.25, y: 1.25 })
    }
    .width(64)
    .height(64)
  }
}
