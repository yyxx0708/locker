import { common, Configuration, ConfigurationConstant } from '@kit.AbilityKit';
import { tapFeedback } from '../common/Haptics';
import { Palette, paletteFor, ThemeController } from '../common/Palette';
import { BindablePerson, WidgetData } from '../common/WidgetData';
import { FormEditSessions } from '../entryformeditability/EntryFormEditAbility';

/**
 * 卡片人物配置页。
 *
 * 由 FormEditExtensionAbility 在添加卡片/编辑卡片时加载。
 * 列出全部人物，单选后点「确定」写回绑定并关闭配置页。
 * 已被其他卡片占用的人物置灰不可选（与 Android 版一致：一个人物只能被一张卡片绑定）。
 */
@Entry
@Component
struct WidgetConfigPage {
  @LocalStorageProp('formId') formId: string = '';

  @State private persons: BindablePerson[] = [];
  @State private selectedId: number = 0;
  @State private loading: boolean = true;
  @State private saving: boolean = false;
  @State private palette: Palette = paletteFor(false);

  aboutToAppear(): void {
    this.refreshPalette();
    this.load();
  }

  onConfigurationUpdate(config: Configuration): void {
    this.refreshPalette(config);
  }

  private refreshPalette(config?: Configuration): void {
    ThemeController.load();
    let systemDark: boolean = false;
    try {
      const currentConfig = config ?? (getContext(this) as common.UIAbilityContext).config;
      systemDark = currentConfig.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
    } catch (err) {
      systemDark = false;
    }
    this.palette = paletteFor(ThemeController.isDark(systemDark));
  }

  private async load(): Promise<void> {
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const persons = await WidgetData.listBindablePersons(context);
      this.persons = persons;
      // 默认选中这张卡片当前绑定的人物
      const current = WidgetData.boundPersonOf(context, this.formId);
      this.selectedId = current;
    } catch (err) {
      this.persons = [];
    } finally {
      this.loading = false;
    }
  }

  /** 某个人物是否被别的卡片占用。 */
  private isOccupied(person: BindablePerson): boolean {
    return person.occupiedBy.length > 0 && person.occupiedBy !== this.formId;
  }

  private async confirm(): Promise<void> {
    if (this.selectedId <= 0 || this.saving) {
      return;
    }
    this.saving = true;
    try {
      const context = getContext(this) as common.UIAbilityContext;
      WidgetData.bind(context, this.formId, this.selectedId);
      await WidgetData.refreshAll(context);
    } catch (err) {
      // 绑定失败时仍然关闭，避免把用户困在配置页
    } finally {
      this.saving = false;
      this.close();
    }
  }

  private close(): void {
    const session = FormEditSessions.take();
    if (session !== null) {
      session.terminateSelf().catch(() => {
      });
    }
  }

  build() {
    Column() {
      Text('选择卡片人物')
        .fontSize(22)
        .fontWeight(FontWeight.Medium)
        .fontColor(this.palette.onSurface)
        .width('100%')
        .margin({ bottom: 4 })

      Text('每张卡片绑定一个人物；已被其他卡片占用的人物不可选。')
        .fontSize(12)
        .fontColor(this.palette.onSurfaceVariant)
        .width('100%')
        .margin({ bottom: 16 })

      if (this.loading) {
        Column({ space: 10 }) {
          LoadingProgress()
            .width(32)
            .height(32)
            .color(this.palette.primary)
          Text('正在读取人物…')
            .fontSize(13)
            .fontColor(this.palette.onSurfaceVariant)
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
      } else if (this.persons.length === 0) {
        Text('还没有人物，请先在 App 的「设置 → 人物」里新建。')
          .fontSize(13)
          .fontColor(this.palette.onSurfaceVariant)
          .width('100%')
          .layoutWeight(1)
      } else {
        Scroll() {
          Column({ space: 8 }) {
            ForEach(this.persons, (person: BindablePerson) => {
              Row({ space: 10 }) {
                Row()
                  .width(18)
                  .height(18)
                  .borderRadius(9)
                  .borderWidth(2)
                  .borderColor(this.selectedId === person.id
                    ? '#3987E5'
                    : (this.isOccupied(person) ? this.palette.outlineVariant : this.palette.outline))
                  .backgroundColor(this.selectedId === person.id ? this.palette.primary : Color.Transparent)

                Column() {
                  Text(person.name)
                    .fontSize(15)
                    .fontColor(this.isOccupied(person) ? this.palette.secondary : this.palette.onSurface)
                    .width('100%')
                  if (this.isOccupied(person)) {
                    Text('已被另一张卡片使用')
                      .fontSize(11)
                      .fontColor(this.palette.secondary)
                      .width('100%')
                  }
                }
                .layoutWeight(1)
                .alignItems(HorizontalAlign.Start)
              }
              .width('100%')
              .padding({ left: 14, right: 14, top: 12, bottom: 12 })
              .borderRadius(16)
              .backgroundColor(this.selectedId === person.id
                ? this.palette.primaryContainer
                : this.palette.surface)
              .opacity(this.isOccupied(person) ? 0.55 : 1)
              .alignItems(VerticalAlign.Center)
              .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
              .onClick(() => {
                if (!this.isOccupied(person)) {
                  this.selectedId = person.id;
                }
              })
            }, (person: BindablePerson) => `${person.id}`)
          }
          .width('100%')
        }
        .layoutWeight(1)
        .scrollBar(BarState.Off)
        .width('100%')
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
          .onClick(() => this.close())

        Button(this.saving ? '保存中…' : '确定')
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .layoutWeight(1)
          .height(46)
          .fontSize(15)
          .fontColor(this.selectedId > 0 && !this.saving
            ? this.palette.onPrimary
            : this.palette.onSurfaceVariant)
          .backgroundColor(this.selectedId > 0 && !this.saving
            ? this.palette.primary
            : this.palette.surfaceContainerHighest)
          .borderRadius(23)
          .enabled(this.selectedId > 0 && !this.saving)
          .onClick(() => this.confirm())
      }
      .width('100%')
      .margin({ top: 16 })
    }
    .width('100%')
    .height('100%')
    .padding(24)
    .backgroundColor(this.palette.background)
    .alignItems(HorizontalAlign.Start)
    .animation({ duration: 250, curve: Curve.EaseOut })
  }
}
