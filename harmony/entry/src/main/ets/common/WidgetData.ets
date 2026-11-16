import { common, Want } from '@kit.AbilityKit';
import { formBindingData, formProvider } from '@kit.FormKit';
import { relationalStore } from '@kit.ArkData';
import { preferences } from '@kit.ArkData';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { todayEpochDay, epochDayOf } from './Dates';
import { formatDurationWithSeconds } from './TimeFormat';

const DOMAIN = 0x0000;
const PREF_NAME = 'locker_settings';
const KEY_WIDGET_BINDING_PREFIX = 'widget_person_';
/**
 * 绑定时的名称快照：key = widget_name_<formId>，value = 绑定当时的人物名。
 * 导入备份会重建人物、id 全变，只有名称还能把卡片找回原人物。
 */
const KEY_WIDGET_NAME_PREFIX = 'widget_name_';
/** 已登记的所有卡片实例；与绑定分开存，未绑定的卡片也要能收到刷新。 */
const KEY_WIDGET_FORM_IDS = 'widget_form_ids';

/**
 * 桌面服务卡片。
 *
 * 与 Android 版一致：每个卡片实例绑定一个人物，只读显示
 * 人物名、本次佩戴时长、是否佩戴、今日是否打卡。
 *
 * 卡片进程与主应用是分开的，拿不到 AppState，所以直接读数据库与偏好：
 * - 绑定关系存在 preferences（key = widget_person_<formId>，value = personId）；
 * - 佩戴状态与分段从 wear_segments 读；
 * - 打卡状态从 daily_photos 判断。
 */
/** 配置页的人物项。occupiedBy 为占用这张人物卡片的其他 formId，空串表示未被占用。 */
export interface BindablePerson {
  id: number;
  name: string;
  occupiedBy: string;
}

export interface WidgetBinding {
  personId: number;
  personName: string;
  deleted: boolean;
  wearing: boolean;
  startAt: number;
  elapsedText: string;
  checkedIn: boolean;
}

/** 导入备份后可恢复的绑定候选。 */
export interface RebindCandidate {
  formId: string;
  personName: string;
  newPersonId: number;
}

/** 导入备份后无法恢复的绑定。 */
export interface RebindFailure {
  formId: string;
  personName: string;
}

export interface RebindPlan {
  candidates: RebindCandidate[];
  failures: RebindFailure[];
}

export class WidgetData {
  /** 读取某个人物的卡片数据；人物不存在时返回 deleted=true。 */
  static async load(context: common.Context, formId: string): Promise<WidgetBinding> {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    const personId = prefs.getSync(`${KEY_WIDGET_BINDING_PREFIX}${formId}`, 0) as number;
    if (personId <= 0) {
      return WidgetData.empty(0, '未绑定人物', false);
    }

    const store = await relationalStore.getRdbStore(context, {
      name: 'locker.db',
      securityLevel: relationalStore.SecurityLevel.S3,
      encrypt: false,
    });

    // 人物名
    const personRs = await store.querySql(
      'SELECT name FROM persons WHERE id = ? LIMIT 1', [personId]);
    let personName = '';
    if (personRs.goToFirstRow()) {
      personName = personRs.getString(personRs.getColumnIndex('name'));
    }
    personRs.close();
    if (personName.length === 0) {
      return WidgetData.empty(personId, '人物已删除', true);
    }
    // 旧版本绑定没有名称快照，这里顺手补上，保证日后导入备份仍能按名称恢复
    if ((prefs.getSync(`${KEY_WIDGET_NAME_PREFIX}${formId}`, '') as string).length === 0) {
      try {
        prefs.putSync(`${KEY_WIDGET_NAME_PREFIX}${formId}`, personName);
        prefs.flushSync();
      } catch (err) {
        hilog.warn(DOMAIN, 'Locker', 'snapshot widget name failed: %{public}s',
          (err as Error).message);
      }
    }

    // 当前佩戴分段
    const segRs = await store.querySql(
      'SELECT startAt FROM wear_segments WHERE personId = ? AND endAt IS NULL ' +
        'ORDER BY startAt DESC LIMIT 1', [personId]);
    let wearing = false;
    let startAt = 0;
    if (segRs.goToFirstRow()) {
      wearing = true;
      startAt = segRs.getLong(segRs.getColumnIndex('startAt'));
    }
    segRs.close();

    // 今日打卡
    const today = todayEpochDay();
    const photoRs = await store.querySql(
      'SELECT EXISTS(SELECT 1 FROM daily_photos WHERE personId = ? AND epochDay = ?) AS e',
      [personId, today]);
    let checkedIn = false;
    if (photoRs.goToFirstRow()) {
      checkedIn = photoRs.getLong(photoRs.getColumnIndex('e')) !== 0;
    }
    photoRs.close();

    const elapsed = wearing ? Math.max(0, Date.now() - startAt) : 0;
    return {
      personId,
      personName,
      deleted: false,
      wearing,
      startAt,
      elapsedText: wearing ? formatDurationWithSeconds(elapsed) : '',
      checkedIn,
    };
  }

  /**
   * 从 onAddForm / onUpdateForm 的 want 里取 formId。
   * 不同场景键名不同：卡片身份用 IDENTITY_KEY，编辑场景用 edit_form_id。
   */
  static formIdOf(want: Want): string {
    const parameters = want.parameters;
    if (parameters === undefined) {
      return '';
    }
    const identity: Object | undefined = parameters['ohos.extra.param.key.form_identity'];
    if (typeof identity === 'string') {
      return identity;
    }
    const edit: Object | undefined = parameters['ohos.extra.param.key.edit_form_id'];
    if (typeof edit === 'string') {
      return edit;
    }
    const plain: Object | undefined = parameters['formId'];
    if (typeof plain === 'string') {
      return plain;
    }
    return '';
  }

  /** 配置页用：读取全部人物与当前绑定情况。 */
  static async listBindablePersons(context: common.Context): Promise<BindablePerson[]> {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    const store = await relationalStore.getRdbStore(context, {
      name: 'locker.db',
      securityLevel: relationalStore.SecurityLevel.S3,
      encrypt: false,
    });
    const rs = await store.querySql('SELECT id, name FROM persons ORDER BY id ASC');
    const persons: BindablePerson[] = [];
    while (rs.goToNextRow()) {
      const id = rs.getLong(rs.getColumnIndex('id'));
      const name = rs.getString(rs.getColumnIndex('name'));
      // 找出占用该人物的其他卡片（排除当前正在配置的这张）
      let occupiedBy = '';
      const all = prefs.getAll();
      for (const key of Object.keys(all)) {
        if (!key.startsWith(KEY_WIDGET_BINDING_PREFIX)) {
          continue;
        }
        const bound = prefs.getSync(key, 0) as number;
        if (bound === id) {
          occupiedBy = key.substring(KEY_WIDGET_BINDING_PREFIX.length);
          break;
        }
      }
      const item: BindablePerson = { id, name, occupiedBy };
      persons.push(item);
    }
    rs.close();
    return persons;
  }

  /** 读取某张卡片当前绑定的人物 id；未绑定返回 0。 */
  static boundPersonOf(context: common.Context, formId: string): number {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    return prefs.getSync(`${KEY_WIDGET_BINDING_PREFIX}${formId}`, 0) as number;
  }

  private static empty(personId: number, name: string, deleted: boolean): WidgetBinding {
    return {
      personId,
      personName: name,
      deleted,
      wearing: false,
      startAt: 0,
      elapsedText: '',
      checkedIn: false,
    };
  }

  /** 绑定卡片到某个人物。 */
  static bind(context: common.Context, formId: string, personId: number): void {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    prefs.putSync(`${KEY_WIDGET_BINDING_PREFIX}${formId}`, personId);
    // 同时记下绑定时的名称：导入备份后人物 id 全变，只有名称还能把卡片找回原人物
    const name = WidgetData.personNameSync(context, personId);
    if (name.length > 0) {
      prefs.putSync(`${KEY_WIDGET_NAME_PREFIX}${formId}`, name);
    } else {
      prefs.deleteSync(`${KEY_WIDGET_NAME_PREFIX}${formId}`);
    }
    WidgetData.registerForm(context, formId);
    prefs.flushSync();
  }

  /** 同步读取人物名。绑定只是要存一个名称快照，不必为它走异步。 */
  private static personNameSync(context: common.Context, personId: number): string {
    try {
      const store = relationalStore.getRdbStoreSync(context, {
        name: 'locker.db',
        securityLevel: relationalStore.SecurityLevel.S3,
        encrypt: false,
      });
      const rs = store.querySqlSync(
        'SELECT name FROM persons WHERE id = ? LIMIT 1', [personId]);
      let name = '';
      if (rs.goToFirstRow()) {
        name = rs.getString(rs.getColumnIndex('name'));
      }
      rs.close();
      return name;
    } catch (err) {
      hilog.warn(DOMAIN, 'Locker', 'read person name failed: %{public}s',
        (err as Error).message);
      return '';
    }
  }

  /** 登记卡片实例。卡片创建时调用，即使还没绑定人物。 */
  static registerForm(context: common.Context, formId: string): void {
    if (formId.length === 0) {
      return;
    }
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    const existing = prefs.getSync(KEY_WIDGET_FORM_IDS, '') as string;
    const ids: string[] = existing.length === 0 ? [] : existing.split(',');
    if (ids.indexOf(formId) < 0) {
      ids.push(formId);
      prefs.putSync(KEY_WIDGET_FORM_IDS, ids.join(','));
      prefs.flushSync();
    }
  }

  static unbind(context: common.Context, formId: string): void {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    prefs.deleteSync(`${KEY_WIDGET_BINDING_PREFIX}${formId}`);
    prefs.deleteSync(`${KEY_WIDGET_NAME_PREFIX}${formId}`);
    // 卡片被移除时同时注销实例登记
    const existing = prefs.getSync(KEY_WIDGET_FORM_IDS, '') as string;
    if (existing.length > 0) {
      const rest = existing.split(',').filter((id: string) => id !== formId);
      prefs.putSync(KEY_WIDGET_FORM_IDS, rest.join(','));
    }
    prefs.flushSync();
  }

  /**
   * 导入备份后生成恢复候选。
   *
   * 导入会清空并重建人物，id 全变，原有绑定全部指向不存在的人物。
   * 这里按「绑定时的名称」与当前人物精确匹配，只生成候选、不静默改绑，
   * 由用户在设置页确认后再调用 applyRebind。
   */
  static async planRebind(context: common.Context): Promise<RebindPlan> {
    const empty: RebindPlan = { candidates: [], failures: [] };
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    const formIds = await WidgetData.activeFormIds(context);
    if (formIds.length === 0) {
      return empty;
    }

    // 当前人物：id → name 与 name → id 两张表
    const store = await relationalStore.getRdbStore(context, {
      name: 'locker.db',
      securityLevel: relationalStore.SecurityLevel.S3,
      encrypt: false,
    });
    const rs = await store.querySql('SELECT id, name FROM persons');
    const existingIds: Set<number> = new Set<number>();
    const idToName: Map<number, string> = new Map<number, string>();
    const idByName: Map<string, number> = new Map<string, number>();
    while (rs.goToNextRow()) {
      const id = rs.getLong(rs.getColumnIndex('id'));
      const name = rs.getString(rs.getColumnIndex('name'));
      existingIds.add(id);
      idToName.set(id, name);
      // 同名人物只取第一个，避免把卡片改绑到不确定的人
      if (!idByName.has(name)) {
        idByName.set(name, id);
      }
    }
    rs.close();

    const candidates: RebindCandidate[] = [];
    const failures: RebindFailure[] = [];
    for (const formId of formIds) {
      const boundId = prefs.getSync(`${KEY_WIDGET_BINDING_PREFIX}${formId}`, 0) as number;
      // 没绑定过：无需恢复
      if (boundId <= 0) {
        continue;
      }
      const personName = prefs.getSync(`${KEY_WIDGET_NAME_PREFIX}${formId}`, '') as string;
      // 仍指向现存人物且名称对得上：无需恢复
      // （自增 id 在清表后可能被复用，名称不符说明已指向另一个人，不能静默认作原人物）
      if (existingIds.has(boundId)) {
        const currentName = idToName.get(boundId) as string;
        if (personName.length === 0 || personName === currentName) {
          continue;
        }
      }
      if (personName.length > 0 && idByName.has(personName)) {
        const newPersonId = idByName.get(personName) as number;
        const candidate: RebindCandidate = { formId, personName, newPersonId };
        candidates.push(candidate);
      } else {
        const failure: RebindFailure = { formId, personName };
        failures.push(failure);
      }
    }
    const plan: RebindPlan = { candidates, failures };
    return plan;
  }

  /**
   * 应用恢复候选。
   * 同一人物只允许一张卡片绑定，重复的保留第一个，其余解绑（与 Android 版一致）。
   */
  static applyRebind(context: common.Context, candidates: RebindCandidate[]): void {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    // 先占位候选之外仍然有效的绑定，避免把别人的位置抢掉
    const used: Set<number> = new Set<number>();
    const all = prefs.getAllSync();
    for (const key of Object.keys(all)) {
      if (!key.startsWith(KEY_WIDGET_BINDING_PREFIX)) {
        continue;
      }
      const formId = key.substring(KEY_WIDGET_BINDING_PREFIX.length);
      let inCandidates = false;
      for (const candidate of candidates) {
        if (candidate.formId === formId) {
          inCandidates = true;
          break;
        }
      }
      if (inCandidates) {
        continue;
      }
      const bound = prefs.getSync(key, 0) as number;
      if (bound > 0) {
        used.add(bound);
      }
    }

    for (const candidate of candidates) {
      if (used.has(candidate.newPersonId)) {
        // 该人物已被别的卡片占用：解绑这张，不重复绑定
        prefs.deleteSync(`${KEY_WIDGET_BINDING_PREFIX}${candidate.formId}`);
        prefs.deleteSync(`${KEY_WIDGET_NAME_PREFIX}${candidate.formId}`);
        continue;
      }
      used.add(candidate.newPersonId);
      prefs.putSync(`${KEY_WIDGET_BINDING_PREFIX}${candidate.formId}`, candidate.newPersonId);
      prefs.putSync(`${KEY_WIDGET_NAME_PREFIX}${candidate.formId}`, candidate.personName);
    }
    prefs.flushSync();
  }

  /** 某个人物当前被哪个卡片占用；没人用返回 null。 */
  static boundFormIdOf(context: common.Context, personId: number): string | null {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    const all = prefs.getAll();
    const keys: string[] = Object.keys(all);
    for (const key of keys) {
      if (!key.startsWith(KEY_WIDGET_BINDING_PREFIX)) {
        continue;
      }
      // 用 getSync 取值，避免对 Record 做下标访问（ArkTS 禁止按索引取字段）
      const bound = prefs.getSync(key, 0) as number;
      if (bound === personId) {
        return key.substring(KEY_WIDGET_BINDING_PREFIX.length);
      }
    }
    return null;
  }

  /** 刷新所有卡片。人物数据变化后调用。 */
  static async refreshAll(context: common.Context): Promise<void> {
    try {
      const formIds = await WidgetData.activeFormIds(context);
      for (const formId of formIds) {
        const data = await WidgetData.load(context, formId);
        await formProvider.updateForm(formId, formBindingData.createFormBindingData({
          personId: data.personId,
          personName: data.personName,
          wearing: data.wearing,
          startAt: data.startAt,
          elapsedText: data.elapsedText,
          checkedIn: data.checkedIn,
          deleted: data.deleted,
        }));
      }
    } catch (err) {
      hilog.warn(DOMAIN, 'Locker', 'widget refresh failed: %{public}s',
        (err as Error).message);
    }
  }

  /**
   * 当前有哪些卡片实例。
   * 鸿蒙没有 Android 的 AppWidgetManager.getAppWidgetIds，
   * 只能从偏好里已登记的绑定键反推。
   */
  static async activeFormIds(context: common.Context): Promise<string[]> {
    const prefs = preferences.getPreferencesSync(context, { name: PREF_NAME });
    const registered = prefs.getSync(KEY_WIDGET_FORM_IDS, '') as string;
    const ids: string[] = registered.length === 0 ? [] : registered.split(',');
    // 兼容旧版本：只有绑定键、没有实例登记时，从绑定键反推
    const all = await prefs.getAll();
    for (const key of Object.keys(all)) {
      if (!key.startsWith(KEY_WIDGET_BINDING_PREFIX)) {
        continue;
      }
      const formId = key.substring(KEY_WIDGET_BINDING_PREFIX.length);
      if (ids.indexOf(formId) < 0) {
        ids.push(formId);
      }
    }
    return ids.filter((id: string) => id.length > 0);
  }
}
