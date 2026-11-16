import { formBindingData, FormEditExtensionAbility, formProvider } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import { UIExtensionContentSession } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { WidgetData } from '../common/WidgetData';

const DOMAIN = 0x0000;

/**
 * 桌面卡片的配置能力。
 *
 * 从桌面添加卡片、或长按卡片选择「编辑」时，系统会拉起这个 UIExtension，
 * 由它加载人物选择页（pages/WidgetConfigPage），让用户把卡片绑定到某个人物。
 *
 * formId 从 want.parameters 里取，键名以 SDK 的 FormParam 为准：
 * 编辑场景通常带 EDIT_FORM_KEY，普通添加场景带 IDENTITY_KEY。
 */
export default class EntryFormEditAbility extends FormEditExtensionAbility {
  onSessionCreate(want: Want, session: UIExtensionContentSession): void {
    const parameters = want.parameters;
    let formId = '';
    if (parameters !== undefined) {
      const editId = parameters[formInfoEditKey()];
      const identityId = parameters[formInfoIdentityKey()];
      if (typeof editId === 'string') {
        formId = editId;
      } else if (typeof identityId === 'string') {
        formId = identityId;
      }
    }
    hilog.info(DOMAIN, 'Locker', 'onSessionCreate formId=%{public}s', formId);

    // 登记实例，保证未绑定时也能收到刷新
    WidgetData.registerForm(this.context, formId);

    const storage: LocalStorage = new LocalStorage();
    storage.setOrCreate<string>('formId', formId);
    session.loadContent('pages/WidgetConfigPage', storage);

    // 记录 session，供页面在绑定完成后主动关闭
    FormEditSessions.put(formId, session);
  }

  onSessionDestroy(session: UIExtensionContentSession): void {
    FormEditSessions.clear();
  }
}

/** 编辑场景的 formId 键。 */
function formInfoEditKey(): string {
  return 'ohos.extra.param.key.edit_form_id';
}

/** 添加卡片场景的 formId 键（FormParam.IDENTITY_KEY）。 */
function formInfoIdentityKey(): string {
  return 'ohos.extra.param.key.form_identity';
}

/**
 * 保存当前配置页的 session。
 * 配置页在绑定完成后需要调用 session.terminateSelf() 关闭自己，
 * 但页面拿不到 session，所以由能力层按 formId 暂存、页面通过这里取。
 */
export class FormEditSessions {
  private static current: UIExtensionContentSession | null = null;

  static put(formId: string, session: UIExtensionContentSession): void {
    FormEditSessions.current = session;
  }

  static take(): UIExtensionContentSession | null {
    return FormEditSessions.current;
  }

  static clear(): void {
    FormEditSessions.current = null;
  }
}
