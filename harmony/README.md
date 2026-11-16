# Locker 鸿蒙原生版

与仓库根目录的 Android 版功能对齐的 HarmonyOS 原生实现，数据格式完全互通。

首次打开工程时，将 `build-profile.example.json5` 复制为 `build-profile.json5`，再由 DevEco Studio 配置本机调试签名。后者包含本机签名信息，不提交到仓库。

## 功能

- 多人物独立计时：每个人物有自己的佩戴状态、分段与事件，可同时佩戴、互不影响
- 锁上 / 开锁带二次确认，佩戴中显示秒级时长；跨天、跨月分段按边界切分统计
- 事件支持自定义类型（名称 + 颜色）；内置「锁射 / 开锁射精」可改名改色、不可删除
- 点事件类型打开编辑框，长按立即记录并可撤销
- 统计：月度月历（佩戴热力 + 事件色点 + 当日照片背景）、年度柱状图与堆叠图
- 每日照片：拍照或从相册选择，显示在月历格子；支持时间水印与离线号码识别
- 打卡：实时时钟、每人每天一个 5 位随机数、今日日报二维码、核验二维码、分享日报卡
- 掌管者同步：按范围打包 AES 加密 zip，接收端验证身份码后逐条比对、勾选应用
- JSON 备份导出 / 导入（含全部人物与随机数，不含照片）
- 桌面服务卡片：只读显示某个人物的佩戴时长与今日打卡
- 外观：跟随系统 / 白天 / 黑暗

## 与 Android 版的数据互通

两端共用同一套数据格式，可以直接互换：

| 内容 | 格式 | 说明 |
|---|---|---|
| JSON 备份 | schemaVersion 4 | 字段名、排序规则一致，可直接互导 |
| 同步包 | zip（manifest.json + payload.bin） | PBKDF2-HMAC-SHA256 派生 AES-256-GCM 密钥 |
| 日报二维码 | `LK2\|salt\|identityId\|...\|signature` | HMAC-SHA256 签名，两端可互相核验 |

加密参数逐字节对齐：PBKDF2 迭代 120000 次、32 字节密钥、12 字节 IV、16 字节 GCM 认证标签、
HMAC 取前 16 字节。改这些参数会破坏互通。

## 目录结构

```
harmony/
├── AppScope/                         应用级配置与图标
├── entry/src/main/ets/
│   ├── common/                       日期、格式化、配色、加密随机、图片、二维码、日报卡
│   ├── data/                         模型、数据库、仓储、备份、同步包与加密
│   ├── domain/                       统计计算、同步范围
│   ├── ui/                           主页 / 统计 / 打卡 / 设置 + 组件
│   ├── widget/                       桌面服务卡片 UI
│   ├── entryability/                 应用入口
│   ├── entryformability/             卡片生命周期
│   └── pages/Index.ets               四页签根页面
└── entry/src/main/resources/         资源与卡片配置
```

## 构建

前提：DevEco Studio 26（或等价的 HarmonyOS SDK 26 工具链）。

```bash
# 用 DevEco Studio 打开 harmony/ 目录，直接运行；或命令行：
export DEVECO_SDK_HOME="<DevEco Studio 安装目录>/sdk"
"<DevEco Studio 安装目录>/tools/hvigor/bin/hvigorw.bat" \
  assembleHap --mode module -p product=default -p buildMode=debug
```

产物：`entry/build/default/outputs/default/entry-default-signed.hap`。

## 签名

`build-profile.json5` 里默认不配置签名。用 DevEco Studio 的
**File → Project Structure → Signing Configs** 自动生成调试签名即可；
正式发布需要自己的证书与 profile。

> 调试签名绑定的 bundleName 必须与 `AppScope/app.json5` 中的一致，
> 否则安装会失败。

## 安装

```bash
hdc install -r entry/build/default/outputs/default/entry-default-signed.hap
hdc shell aa start -a EntryAbility -b com.yxsdc.locker
```

## 已知限制

- 桌面卡片不支持定时器：佩戴时长由卡片刷新时计算，系统按 `form_config.json`
  里的 `updateDuration` 周期刷新
- 卡片绑定关系存放在偏好里（`widget_person_<formId>`），删除卡片会解绑
- 分享日报卡使用 `drawing` 离屏绘制，部分低端设备可能因 GPU 能力受限而失败（返回 null 并提示）

## 许可证

[MIT](../LICENSE) © 2026 yyxx0708
