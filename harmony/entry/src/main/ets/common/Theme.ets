import { ConfigurationConstant } from '@kit.AbilityKit';
import { ThemeMode, parseThemeMode } from './ThemeMode';
import { AppPreferences } from '../data/AppPreferences';

export { ThemeMode } from './ThemeMode';

/**
 * 主题偏好的读写。
 */
export class ThemePreferences {
  static read(): ThemeMode {
    return AppPreferences.themeMode();
  }

  static write(mode: ThemeMode): void {
    AppPreferences.setThemeMode(mode);
  }

  /** 把主题模式换成系统 ColorMode，交给 setColorMode 使用。 */
  static toColorMode(mode: ThemeMode): ConfigurationConstant.ColorMode {
    switch (mode) {
      case ThemeMode.LIGHT:
        return ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT;
      case ThemeMode.DARK:
        return ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
      default:
        return ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET;
    }
  }
}

/** 兼容旧调用点。 */
export function parseTheme(value: string | undefined | null): ThemeMode {
  return parseThemeMode(value);
}
