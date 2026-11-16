# HarmonyOS File Open Notes

Checked against the installed HarmonyOS API 26 SDK on 2026-11-15.

## Ability matching

The SDK's `module.json` schema accepts several URI filter shapes. For the system file-opening chooser, Huawei's dedicated guide requires a `viewData` skill with `scheme: "file"`, a specific `type`, and the case-sensitive `linkFeature: "FileOpen"`. It explicitly does not support `*/*`. The ZIP UTD `general.zip-archive` is preferred; common ZIP MIME types remain listed for callers using MIME. A separate `sendData` skill handles sharing.

The SDK defines `ohos.want.action.viewData` as the action for viewing data and `ohos.want.action.sendData` as sending a single data item. It defines `entity.system.default` as the default entity used when a Want does not specify one.

## Remaining runtime evidence

The project declares `FileOpen` for ZIP files and a separate `sendData` filter for shared ZIP files. Confirm chooser visibility in Files and QQ on-device. If QQ still differs, capture its action, URI and type from the incoming Want or resolver diagnostics.

## Sources

- [Core File Kit简介](https://developer.huawei.com/consumer/cn/doc/doccenter-capabilities/core-file-kit-intro) — confirms file sharing through URI or file descriptor, but does not specify QQ's resolver action or MIME type.
- [拉起文件处理类应用（startAbility）](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/file-processing-apps-startup) — target app must declare `scheme: "file"`, a concrete `type`, and `linkFeature: "FileOpen"`; `*/*` is unsupported.
- `D:/DevEco Studio/sdk/default/hms/toolchains/modulecheck/module.json` — API 26 `module.json` JSON schema, Ability `skills`, `actions`, `entities`, and `uris` forms.
- `D:/DevEco Studio/sdk/default/openharmony/ets/api/@ohos.ability.wantConstant.d.ts` — system actions and entities, including `ACTION_VIEW_DATA`, `ACTION_SEND_DATA`, and `ENTITY_DEFAULT`.
- `D:/DevEco Studio/sdk/default/openharmony/ets/api/@ohos.app.ability.Want.d.ts` — Want `uri` and MIME `type` matching information.
- `harmony/entry/src/main/module.json5` — this app's installed file-open filters.
