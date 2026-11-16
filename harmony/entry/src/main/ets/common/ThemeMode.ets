/**
 * 主题模式。与 Android 版一致：跟随系统 / 白天 / 黑暗。
 */
export enum ThemeMode {
  SYSTEM = 'SYSTEM',
  LIGHT = 'LIGHT',
  DARK = 'DARK',
}

export function themeModeLabel(mode: ThemeMode): string {
  switch (mode) {
    case ThemeMode.LIGHT:
      return '白天';
    case ThemeMode.DARK:
      return '黑暗';
    default:
      return '跟随系统';
  }
}

export function parseThemeMode(value: string | undefined | null): ThemeMode {
  if (value === ThemeMode.LIGHT) {
    return ThemeMode.LIGHT;
  }
  if (value === ThemeMode.DARK) {
    return ThemeMode.DARK;
  }
  return ThemeMode.SYSTEM;
}
