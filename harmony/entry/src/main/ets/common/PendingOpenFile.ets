/**
 * 外部应用交进来的同步包暂存区。
 *
 * 在 QQ / 文件管理器里「用其他应用打开」zip 时，系统把文件以 want.uri
 * 交给 EntryAbility；Ability 的生命周期比页面早，先把 URI 放到这里，
 * 页面加载完成后再由 Index 轮询取走并打开接收弹窗。
 *
 * 只保留最近一次，避免连续分享时堆积；取走即清空（消费标记）。
 */
export class PendingOpenFile {
  private static pendingUri: string = '';
  /** 快捷方式携带的动作（如 sendToday），页面消费后执行对应流程。 */
  private static pendingAction: string = '';

  /** Ability 收到外部文件时写入。空串忽略。 */
  static put(uri: string): void {
    if (uri.length > 0) {
      PendingOpenFile.pendingUri = uri;
    }
  }

  /** 取出并清空；没有待处理文件时返回空串。 */
  static take(): string {
    const uri = PendingOpenFile.pendingUri;
    PendingOpenFile.pendingUri = '';
    return uri;
  }

  /** 记录快捷方式动作。 */
  static putAction(action: string): void {
    if (action.length > 0) {
      PendingOpenFile.pendingAction = action;
    }
  }

  /** 取出并清空动作；没有时返回空串。 */
  static takeAction(): string {
    const action = PendingOpenFile.pendingAction;
    PendingOpenFile.pendingAction = '';
    return action;
  }

  /** 只查看不消费。 */
  static peek(): string {
    return PendingOpenFile.pendingUri;
  }

  /** 丢弃待处理项（例如文件不是同步包时）。 */
  static clear(): void {
    PendingOpenFile.pendingUri = '';
  }
}
