import { formBindingData, FormExtensionAbility, formInfo, formProvider } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { WidgetData } from '../common/WidgetData';

const DOMAIN = 0x0000;

/**
 * 桌面服务卡片的生命周期。
 *
 * 数据由 WidgetData 从数据库与偏好里组装；这里只负责
 * 新增、更新、删除时的数据绑定。
 */
export default class EntryFormAbility extends FormExtensionAbility {
  onAddForm(want: Want): formBindingData.FormBindingData {
    const formId = WidgetData.formIdOf(want);
    hilog.info(DOMAIN, 'Locker', 'onAddForm formId=%{public}s', formId);
    // 登记实例：即使还没绑定人物，之后也能收到刷新
    WidgetData.registerForm(this.context, formId);
    // 先返回占位数据；真实数据在读到后通过 updateForm 推上去
    WidgetData.load(this.context, formId).then((data) => {
      const binding = formBindingData.createFormBindingData({
        personId: data.personId,
        personName: data.personName,
        wearing: data.wearing,
        startAt: data.startAt,
        elapsedText: data.elapsedText,
        checkedIn: data.checkedIn,
        deleted: data.deleted,
      });
      formProvider.updateForm(formId, binding).catch(() => {
      });
    }).catch(() => {
    });
    return formBindingData.createFormBindingData({
      personId: 0,
      personName: 'Locker',
      wearing: false,
      startAt: 0,
      elapsedText: '',
      checkedIn: false,
      deleted: false,
    });
  }

  onCastToNormalForm(formId: string): void {
  }

  onUpdateForm(formId: string): void {
    hilog.info(DOMAIN, 'Locker', 'onUpdateForm %{public}s', formId);
    WidgetData.load(this.context, formId).then((data) => {
      const binding = formBindingData.createFormBindingData({
        personId: data.personId,
        personName: data.personName,
        wearing: data.wearing,
        startAt: data.startAt,
        elapsedText: data.elapsedText,
        checkedIn: data.checkedIn,
        deleted: data.deleted,
      });
      formProvider.updateForm(formId, binding).catch((err: Error) => {
        hilog.warn(DOMAIN, 'Locker', 'updateForm failed: %{public}s', err.message);
      });
    }).catch(() => {
    });
  }

  /**
   * 卡片点击「未绑定人物」时，卡片会发一条 message 过来；
   * 这里打开卡片编辑页，让用户选人物。
   */
  onFormEvent(formId: string, message: string): void {
    hilog.info(DOMAIN, 'Locker', 'onFormEvent %{public}s %{public}s', formId, message);
    try {
      formProvider.openFormEditAbility('EntryFormEditAbility', formId);
    } catch (err) {
      hilog.warn(DOMAIN, 'Locker', 'openFormEditAbility failed: %{public}s',
        (err as Error).message);
    }
  }

  onRemoveForm(formId: string): void {
    hilog.info(DOMAIN, 'Locker', 'onRemoveForm %{public}s', formId);
    WidgetData.unbind(this.context, formId);
  }

  onAcquireFormState(want: Want): formInfo.FormState {
    return formInfo.FormState.READY;
  }
}
