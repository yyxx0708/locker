# Locker

本地离线的 Android 佩戴计时与事件记录 App。

## 功能

- 多人物独立计时：每个人物有自己的佩戴状态、分段与事件，可同时佩戴、互不影响
- 单一佩戴状态（按人物）：锁上 / 开锁，自动生成佩戴分段；主页操作有二次确认，避免误触
- 事件支持自定义类型：可设置名称与统计颜色；内置「锁射 / 开锁射精」也可改名改色
- 每次事件一条记录（类型 + 时间 + 可选备注），归属于当前人物
- 分段与事件均可补录、修改、删除
- 外观支持跟随系统 / 白天 / 黑暗三种模式
- 统计跟随当前人物：月度统计为日历视图（佩戴热力 + 事件标记，点按日期看当天明细），年度统计为柱状图
- 每日照片：在日期详情或随机数页上传照片，照片会成为月历格子的背景；只能上传今天及之前的日期，可更换、删除
- 打卡：显示实时日期时间；每个人物每天固定一个 5 位数字（10000–99999），当天不可更换，不同人物的号码不同
- 今日照片：随机数页支持拍照 / 相册上传或更换当前人物的今日照片
- 数据仅存本机，支持 JSON 导出 / 导入备份（包含全部人物）

## 技术栈

Kotlin、Jetpack Compose、Room、Material 3；minSdk 26，targetSdk 34。

## 构建

前提：JDK 17、Android SDK Platform 34。

使用项目内 wrapper（首次需要联网下载 Gradle）：

```powershell
.\gradlew.bat :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。

## 安装

```powershell
adb install -r Locker-1.9.apk
```

仓库不包含签名文件（`locker-release.keystore`、`keystore.properties`）与 APK 产物，正式签名需要自备。

## 签名

`locker-release.keystore` 与 `keystore.properties` 是升级签名的关键，**必须备份**。丢失后无法覆盖安装新版本，只能卸载重装。不要把这两个文件发给别人。

## 数据

- 数据库：本机 `locker.db`（Room），分段与事件按人物归属，事件类型单独存储
- 备份：设置页导出 JSON（包含全部人物、事件类型与每日随机数）；导入会整体替换现有数据
- 旧版 1.1 / 1.2 备份仍可导入，旧记录归入默认人物「我」，事件类型使用内置两项
- 每日照片保存在本机数据库，**不包含在 JSON 备份中**；导入备份会清空每日照片
- 无网络权限、无账号、无云同步、无系统自动备份

## 文档

- [使用手册](docs/使用手册.md)
- [CONTEXT.md](CONTEXT.md)
- [ADR 0001：本地优先存储](docs/adr/0001-local-first-storage.md)
- [ADR 0002：精确时长而非日历天数](docs/adr/0002-precise-duration.md)
- [ADR 0003：多人物独立计时](docs/adr/0003-multi-person.md)
- [ADR 0004：自定义事件类型](docs/adr/0004-custom-event-types.md)
- [ADR 0005：每日照片](docs/adr/0005-daily-photos.md)
- [ADR 0006：按人物与日期固定的随机数](docs/adr/0006-daily-random-number.md)
