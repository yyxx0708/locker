import { EventTypeEntity } from '../../data/Models';
import { colorString } from '../../common/EventColors';
import { Palette } from '../../common/Palette';

/**
 * 通用小组件：色点、卡片、空状态、区块标题。
 * 视觉参数与 Android 版对齐（圆角 20vp、卡片底色 surface、色点 8vp 起）。
 */

@Builder
export function EventColorDot(colorArgb: number, size: number, borderColor?: string) {
  Row()
    .width(size)
    .height(size)
    .borderRadius(size / 2)
    .backgroundColor(colorString(colorArgb))
    .borderWidth(borderColor === undefined ? 0 : 1)
    .borderColor(borderColor ?? '#00000000')
}

@Builder
export function EventTypeLabel(type: EventTypeEntity, textColor: string, fontSize: number) {
  Row({ space: 6 }) {
    EventColorDot(type.colorArgb, 8)
    Text(type.name)
      .fontSize(fontSize)
      .fontColor(textColor)
  }
  .alignItems(VerticalAlign.Center)
}

/** 空状态提示条：圆形信息图标 + 一行说明。 */
@Builder
export function EmptyStateHint(palette: Palette, text: string) {
  Row({ space: 10 }) {
    Row() {
      Text('i')
        .fontSize(12)
        .fontWeight(FontWeight.Bold)
        .fontColor(palette.onSurfaceVariant)
    }
    .width(28)
    .height(28)
    .borderRadius(14)
    .backgroundColor(palette.surfaceContainerHighest)
    .justifyContent(FlexAlign.Center)

    Text(text)
      .fontSize(12)
      .fontColor(palette.onSurfaceVariant)
      .layoutWeight(1)
  }
  .width('100%')
  .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  .borderRadius(16)
  .backgroundColor(palette.surfaceContainerLow)
  .alignItems(VerticalAlign.Center)
}

/** 区块标题 + 右侧说明。 */
@Builder
export function SectionHeader(
  title: string,
  hint: string,
  titleColor: string,
  hintColor: string,
) {
  Row() {
    Text(title)
      .fontSize(16)
      .fontWeight(FontWeight.Medium)
      .fontColor(titleColor)
    Blank()
    if (hint.length > 0) {
      Text(hint)
        .fontSize(12)
        .fontColor(hintColor)
    }
  }
  .width('100%')
  .alignItems(VerticalAlign.Center)
}

/** 细分割线。 */
@Builder
export function LockerDivider(color: string) {
  Divider()
    .strokeWidth(1)
    .color(color)
}
