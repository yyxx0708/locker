import { Palette } from '../../common/Palette';
import { EventTypeEntity, Person } from '../../data/Models';
import { tapFeedback } from '../../common/Haptics';
import { EventColorDot } from './Common';

/**
 * 人物切换条与事件类型按钮。
 */

/** 人物切换条：横向滚动的胶囊按钮。 */
@Component
export struct PersonSelector {
  @Prop palette: Palette;
  @Prop persons: Person[] = [];
  @Prop selectedPersonId: number = -1;
  onSelect: (person: Person) => void = () => {
  };

  build() {
    Scroll() {
      Row({ space: 8 }) {
        ForEach(this.persons, (person: Person) => {
          Row() {
            Text(person.name)
              .fontSize(person.id === this.selectedPersonId ? 15 : 14)
              .fontWeight(person.id === this.selectedPersonId ? FontWeight.Bold : FontWeight.Medium)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .fontColor(person.id === this.selectedPersonId
                ? this.palette.onSecondaryContainer
                : this.palette.onSurfaceVariant)
          }
          .height(48)
          .padding({ left: 16, right: 16 })
          .borderRadius(20)
          .borderWidth(1)
          .borderColor(person.id === this.selectedPersonId
            ? '#00000000'
            : this.palette.outline)
          .backgroundColor(person.id === this.selectedPersonId
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
          .animation({ duration: 200, curve: Curve.EaseOut })
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .onClick(() => this.onSelect(person))
        }, (person: Person) => `${person.id}`)
      }
    }
    .scrollable(ScrollDirection.Horizontal)
    .scrollBar(BarState.Off)
    .width('100%')
  }
}
/** 主页的事件类型按钮：点一下打开编辑框，长按立即记录。 */
@Component
export struct EventTypeButtons {
  @Prop palette: Palette;
  @Prop eventTypes: EventTypeEntity[] = [];
  @State private pressedTypeKey: string = '';
  onClickType: (type: EventTypeEntity) => void = () => {
  };
  onLongPressType: (type: EventTypeEntity) => void = () => {
  };

  build() {
    Flex({ wrap: FlexWrap.Wrap }) {
      ForEach(this.eventTypes, (type: EventTypeEntity) => {
        Row({ space: 8 }) {
          EventColorDot(type.colorArgb, 10)
          Text(type.name)
            .fontSize(14)
            .fontColor(this.palette.onSurface)
        }
        .height(48)
        .padding({ left: 16, right: 16 })
        .borderRadius(16)
        .backgroundEffect({ radius: 14, saturation: 1, color: this.palette.dark ? '#A82A2A2A' : '#B8FFFFFF' })
        .linearGradient({ angle: 135, colors: [
          ['#55FFFFFF', 0], ['#14FFFFFF', 0.32],
          ['#05FFFFFF', 0.68], ['#28FFFFFF', 1],
        ] })
        .shadow({ radius: 7, color: '#14000000', offsetY: 1 })
        .margin({ right: 10, bottom: 10 })
        .alignItems(VerticalAlign.Center)
        .scale({
          x: this.pressedTypeKey === type.typeKey ? 0.97 : 1,
          y: this.pressedTypeKey === type.typeKey ? 0.97 : 1,
        })
        .opacity(this.pressedTypeKey === type.typeKey ? 0.7 : 1)
        .animation({ duration: 100, curve: Curve.EaseOut })
        .onTouch((event: TouchEvent) => {
          if (event.type === TouchType.Down) {
            this.pressedTypeKey = type.typeKey;
          } else if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
            this.pressedTypeKey = '';
          }
        })
        // 用 GestureGroup 明确串行：短按走 TapGesture，长按走 LongPressGesture，
        // 两者互斥，避免 onClick 与 gesture 同时挂在一个节点上时长按被吞掉
        .gesture(
          GestureGroup(GestureMode.Exclusive,
            LongPressGesture({ repeat: false, duration: 400 })
              .onAction(() => {
                tapFeedback();
                this.onLongPressType(type);
              }),
            TapGesture({ count: 1 })
              .onAction(() => {
                tapFeedback();
                this.onClickType(type);
              }),
          ),
        )
      }, (type: EventTypeEntity) => type.typeKey)
    }
    .width('100%')
  }
}
