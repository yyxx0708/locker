/**
 * 桌面服务卡片的 UI。
 *
 * 与 Android 版一致：佩戴中整卡转为主色容器色，显示呼吸圆点与「佩戴中」，
 * 以及本次已持续时长；未佩戴时显示「未佩戴」。
 * 底部是打卡状态徽标：✓ 今日已打卡（蓝）/ 今日未打卡（灰）。
 * 卡片只读，点卡片打开 App。
 *
 * 时长用 TextTimer 走字：卡片进程里不能开定时器，但 TextTimer 是系统组件，
 * 传 startTime（本次佩戴开始的毫秒时间戳）后由系统自己按秒刷新，
 * 效果等同 Android 版的秒表控件，不需要卡片服务频繁 updateForm。
 */
@Entry
@Component
struct LockerWidgetCard {
  @LocalStorageProp('personName') personName: string = 'Locker';
  @LocalStorageProp('wearing') wearing: boolean = false;
  @LocalStorageProp('elapsedText') elapsedText: string = '';
  @LocalStorageProp('checkedIn') checkedIn: boolean = false;
  @LocalStorageProp('deleted') deleted: boolean = false;
  @LocalStorageProp('startAt') startAt: number = 0;
  @LocalStorageProp('personId') personId: number = 0;
  /** 呼吸圆点的透明度，由无限往返动画驱动。 */
  @State private dotAlpha: number = 1;
  /** 卡片尺寸：2*2 用紧凑版式，4*4 用舒展版式。 */
  @LocalStorageProp('cardWidth') cardWidth: number = 2;

  private cardBackground(): string {
    return this.wearing ? '#D7E7FB' : '#FCFCFB';
  }

  /** 佩戴中时内容色用深蓝，保证在浅蓝底上可读。 */
  private inkColor(): string {
    return this.wearing ? '#0B2B4F' : '#0B0B0B';
  }

  private subColor(): string {
    return this.wearing ? '#3D5A80' : '#52514E';
  }

  /** 4*4 尺寸下字号放大一档。 */
  private titleSize(): number {
    return this.cardWidth >= 4 ? 22 : 18;
  }

  private timerSize(): number {
    return this.cardWidth >= 4 ? 34 : 26;
  }

  build() {
    Column() {
      Row() {
        Text(this.personName)
          .fontSize(this.titleSize())
          .fontWeight(FontWeight.Medium)
          .fontColor(this.inkColor())
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        if (this.wearing) {
          // 呼吸圆点：卡片支持属性动画
          Row()
            .width(8)
            .height(8)
            .borderRadius(4)
            .backgroundColor('#2A78D6')
            .opacity(this.dotAlpha)
            .onAppear(() => {
              // 卡片支持属性动画：一条无限往返代替定时器
              animateTo({
                duration: 900,
                curve: Curve.EaseInOut,
                iterations: -1,
                playMode: PlayMode.Alternate,
              }, () => {
                this.dotAlpha = 0.3;
              });
            })
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)

      Blank()

      if (this.deleted) {
        Text('人物已删除')
          .fontSize(13)
          .fontColor('#52514E')
          .width('100%')
        Text('点击重新选择人物')
          .fontSize(11)
          .fontColor('#6E6C66')
          .width('100%')
      } else if (this.wearing && this.startAt > 0) {
        // 系统秒表：从本次佩戴开始时刻走字，卡片服务不需要每秒刷新
        TextTimer({ isCountDown: false, startTime: this.startAt, count: 0 })
          .format('hh:mm:ss')
          .fontSize(this.timerSize())
          .fontWeight(FontWeight.Medium)
          .fontFamily('HarmonyOS Sans Mono')
          .fontColor('#0B2B4F')
          .width('100%')
        Text('佩戴中')
          .fontSize(11)
          .fontColor(this.subColor())
          .width('100%')
      } else if (this.wearing) {
        // 没有开始时刻（异常数据）时退回卡片服务算好的静态时长
        Text(this.elapsedText.length > 0 ? this.elapsedText : '佩戴中')
          .fontSize(this.timerSize())
          .fontWeight(FontWeight.Medium)
          .fontFamily('HarmonyOS Sans Mono')
          .fontColor('#0B2B4F')
          .width('100%')
          .maxLines(1)
        Text('佩戴中')
          .fontSize(11)
          .fontColor(this.subColor())
          .width('100%')
      } else {
        Text('未佩戴')
          .fontSize(this.titleSize())
          .fontColor('#52514E')
          .width('100%')
      }

      Blank()

      Row() {
        Text(this.checkedIn ? '✓ 今日已打卡' : '今日未打卡')
          .fontSize(11)
          .fontColor(this.checkedIn ? '#2A78D6' : '#6E6C66')
          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .borderRadius(10)
          .backgroundColor(this.checkedIn ? '#1A2A78D6' : '#1A6E6C66')
      }
      .width('100%')
    }
    .width('100%')
    .height('100%')
    .padding(16)
    .backgroundColor(this.cardBackground())
    .borderRadius(20)
    .onClick(() => {
      if (this.deleted || this.personId <= 0) {
        // 未绑定或人物已删除：通知卡片服务打开配置页重新选择人物
        postCardAction(this, {
          action: 'message',
          params: { wantToBind: true },
        });
      } else {
        // 已绑定：打开 App 并切到该人物（与 Android 版一致）
        postCardAction(this, {
          action: 'router',
          abilityName: 'EntryAbility',
          params: { widgetPersonId: this.personId },
        });
      }
    })
  }
}
