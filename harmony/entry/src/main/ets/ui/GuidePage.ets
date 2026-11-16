import { Palette } from '../common/Palette';

/**
 * 应用内使用指南：独立整页，从设置页「关于 → 使用指南」进入。
 *
 * 内容与 Android 版 GuideScreen.kt 及 docs/使用手册.md 对齐，只保留用户最常用的部分。
 * 章节一次只展开一个：点击卡片在「展开 / 收起」之间切换。
 */

/** 一个可折叠章节。 */
interface GuideSection {
  title: string;
  paragraphs: string[];
}

/**
 * 章节文案。以 "- " 开头的段落渲染成项目符号行，其余按普通段落。
 * 顺序与 Android 版一致，改文案时请同步那边，避免两端说明打架。
 */
const GUIDE_SECTIONS: GuideSection[] = [
  {
    title: '1. 认识 Locker',
    paragraphs: [
      'Locker 是一个纯本地的佩戴计时工具。它为每个「人物」独立记录佩戴分段和事件，每天生成一个 5 位随机数，并可以把指定时间段的数据加密打包，交给掌管者核对。',
      '- 人物：独立计时对象，可以同时有多个处于佩戴中',
      '- 分段：一次「锁上 → 开锁」之间的时间，跨天会按天切分',
      '- 随机数：每天每人一个 5 位数字，同一天不同人物不重复',
      '- 身份码：8 位，形如 K7QM-3XPD；App 里也叫「验证码」',
    ],
  },
  {
    title: '2. 三分钟上手',
    paragraphs: [
      '1. 设置 → 人物 → 右上角加号，建立人物',
      '2. 回到主页，点「锁上」并确认，开始计时',
      '3. 点或长按事件类型，记录一次事件',
      '4. 打开「打卡」页查看今天的随机数',
      '5. 在「统计」页查看每天的佩戴时长与明细',
      '6. 设置 → 数据 → 导出备份，确认文件已保存',
    ],
  },
  {
    title: '3. 主页：佩戴与事件',
    paragraphs: [
      '顶部选择器用于切换当前人物。每个人物的计时、事件和统计互相独立。',
      '- 佩戴中会实时显示本次已持续的时间和开始时间',
      '- 同一人物同时只能有一个佩戴中的分段',
      '- 同一人物的分段之间不能重叠；结束时间不能早于开始时间',
      '- 点事件类型打开编辑框；长按立即记录，并可在提示条点「撤销」',
    ],
  },
  {
    title: '4. 打卡：随机数、日报与照片',
    paragraphs: [
      '每天首次打开 App 或进入打卡页时，会为当前人物生成当天的 5 位随机数；当天内不会改变。',
      '- 点击随机数卡片进入全屏横屏显示，方便拍照',
      '- 全屏底部有「镜像」开关：前置摄像头拍摄时打开它，拍到的数字方向才正确',
      '- 「今日日报」生成带签名的二维码，对方在 Locker 里「核验二维码」即可确认真伪',
      '- 「分享日报卡」把当天信息合成一张竖图，可直接发到聊天工具',
      '- 今日照片保存在本机，可更换或删除；只允许上传今天及之前的日期',
      '- 设置里可开启「上传时烧录时间水印」和「上传后检测随机数」',
    ],
  },
  {
    title: '5. 统计：时长与明细',
    paragraphs: [
      '顶部可切换「月」「年」，用左右箭头翻期，离开当前周期后会出现「回到本期」。',
      '- 月历格子颜色越深，当天佩戴越久；彩色圆点是当天的事件类型',
      '- 上传过照片的日期会以照片作为格子背景',
      '- 点任意日期查看当天的时间轴、事件、照片和分段',
      '- 「补录」可以手动添加一段历史佩戴时间',
      '- 跨天分段按实际时间切分计入各天',
    ],
  },
  {
    title: '6. 设置：人物与事件类型',
    paragraphs: [
      '人物名称最多 20 个字符、不能重名，至少保留一个人物。删除人物会同时删除该人物的分段、事件、照片、随机数和同步身份。',
      '- 事件类型可以新增、改名、改色',
      '- 内置类型不能删除，自定义类型删除时会连同所有人物下该类型的事件一起删除',
    ],
  },
  {
    title: '7. 掌管者同步',
    paragraphs: [
      '同步把当前人物在所选时间段内的分段、事件、随机数和照片加密打包，接收端验证身份码后逐条比对。',
      '- 佩戴者：设置 → 发送给掌管者，或打卡页「发送今日数据」',
      '- 也可以只发上次发送之后的数据，避免重复传输',
      '- 接收方：长按文件用 Locker 打开，或设置 → 接收掌管者数据',
      '- 差异列表默认只勾选新增和修改；删除项需要手动勾选并二次确认',
      '- 身份码既是身份也是解密钥匙，尽量用不同渠道告知对方',
    ],
  },
  {
    title: '8. 桌面小组件',
    paragraphs: [
      '长按桌面空白处 → 小组件 → 找到 Locker，把「Locker 佩戴状态」拖到桌面；添加时会让你选择绑定哪个人物。',
      '- 每个小组件只显示一个人物；同一个人物同时只能被一个小组件使用',
      '- 佩戴中显示本次已持续时长，未佩戴时显示「未佩戴」',
      '- 同时显示今日是否已打卡（是否上传了今日照片）',
      '- 小组件是只读的：点卡片会打开 App，并切换到该人物',
      '- 支持 2×2 与 4×2 两种大小，跟随系统深色模式',
      '- 人物被删除后，小组件会显示「人物已删除」，点它可重新选择',
      '- 导入备份后人物编号会变，App 会提示哪些小组件可按名称恢复绑定，需要你确认',
    ],
  },
  {
    title: '9. 备份与恢复',
    paragraphs: [
      '设置 → 数据 → 导出备份，生成 JSON 文件；导入会替换当前全部数据。',
      '- 备份包含全部人物、事件类型、分段、事件和每日随机数',
      '- 不包含每日照片、同步身份绑定和外观设置',
      '- 换手机前先导出备份；需要照片时用「全部导出」发送同步包',
      '- 卸载或清除应用数据会删除全部记录，且无系统自动备份',
    ],
  },
  {
    title: '10. 常见问题',
    paragraphs: [
      '某天没有随机数？——那天没有打开过 App，不会补生成过去日期的号码。',
      '导入备份后照片没了？——JSON 备份不含照片，需用同步包迁移。',
      '核验提示验证码不正确？——先确认身份码，再让对方重新打包。',
      '二维码识别不出来？——让对方在日报弹窗里原样截图、不要裁边。',
      '找不到 QQ 发来的 zip？——长按文件 →「用其他应用打开」→ Locker。',
    ],
  },
];

@Component
export struct GuidePage {
  @Prop palette: Palette;
  onBack: () => void = () => {
  };

  /** 当前展开的章节标题；空串表示全部收起（同一时间只展开一章）。 */
  @State private expanded: string = '';

  private toggle(title: string): void {
    this.expanded = this.expanded === title ? '' : title;
  }

  build() {
    Column() {
      // 顶部返回栏
      Row() {
        Text('‹')
          .fontSize(26)
          .fontColor(this.palette.onSurface)
          .width(44)
          .height(44)
          .textAlign(TextAlign.Center)
          .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
          .onClick(() => this.onBack())
        Text('使用指南')
          .fontSize(22)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.palette.onSurface)
          .margin({ left: 2 })
      }
      .width('100%')
      .padding({ left: 4, right: 16, top: 8, bottom: 4 })
      .alignItems(VerticalAlign.Center)

      Scroll() {
        Column({ space: 10 }) {
          Text('Locker 2.2.5 · 本地离线的佩戴计时与事件记录工具')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .padding({ left: 4, right: 4 })

          ForEach(GUIDE_SECTIONS, (section: GuideSection) => {
            Column({ space: 8 }) {
              Row() {
                Text(section.title)
                  .fontSize(16)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(this.palette.onSurface)
                  .layoutWeight(1)
                Text(this.expanded === section.title ? '收起' : '展开')
                  .fontSize(13)
                  .fontWeight(FontWeight.Medium)
                  .fontColor(this.palette.primary)
              }
              .width('100%')
              .alignItems(VerticalAlign.Center)

              if (this.expanded === section.title) {
                Column({ space: 6 }) {
                  ForEach(section.paragraphs, (line: string) => {
                    if (line.startsWith('- ')) {
                      // 项目符号行：圆点固定在行首，长文本换行后仍对齐
                      Row({ space: 8 }) {
                        Text('•')
                          .fontSize(14)
                          .fontColor(this.palette.onSurfaceVariant)
                        Text(line.substring(2))
                          .fontSize(14)
                          .fontColor(this.palette.onSurfaceVariant)
                          .layoutWeight(1)
                      }
                      .width('100%')
                      .alignItems(VerticalAlign.Top)
                    } else {
                      Text(line)
                        .fontSize(14)
                        .fontColor(this.palette.onSurface)
                        .width('100%')
                    }
                  }, (line: string, index: number) => `${section.title}#${index}`)
                }
                .width('100%')
                .alignItems(HorizontalAlign.Start)
                // 展开时淡入，收起时淡出
                .transition(TransitionEffect.OPACITY.animation({ duration: 200 }))
              }
            }
            .width('100%')
            .padding(16)
            .backgroundColor(this.palette.surface)
            .borderRadius(20)
            .alignItems(HorizontalAlign.Start)
            // 卡片高度随展开 / 收起平滑变化
            .animation({ duration: 250, curve: Curve.EaseOut })
            .clickEffect({ level: ClickEffectLevel.MIDDLE, scale: 0.97 })
            .onClick(() => {
              this.toggle(section.title);
            })
          }, (section: GuideSection) => section.title)

          Text('以上内容与设置页功能一致；遇到问题可先查看对应章节。')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .padding({ left: 4, right: 4 })

          Text('Locker 由 yelans 开发与维护 · QQ 3781994974')
            .fontSize(12)
            .fontColor(this.palette.onSurfaceVariant)
            .width('100%')
            .padding({ left: 4, right: 4 })
        }
        .width('100%')
        .padding({ left: 16, right: 16, top: 8, bottom: 24 })
        .alignItems(HorizontalAlign.Start)
      }
      .scrollBar(BarState.Off)
      .layoutWeight(1)
      .width('100%')
      .align(Alignment.TopStart)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette.background)
  }
}
