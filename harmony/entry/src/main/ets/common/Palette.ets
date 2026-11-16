import { ThemeMode } from './ThemeMode';
import { AppPreferences } from '../data/AppPreferences';

/**
 * 配色。
 *
 * 数值与 Android 版 Material 3 配色一致，切换主题时两端的观感相同。
 * ArkUI 没有 Material 的 surfaceContainer 层级，这里把用到的层级逐个列出，
 * 组件按语义取色，而不是到处写死十六进制。
 */
export interface Palette {
  dark: boolean;
  primary: string;
  onPrimary: string;
  primaryContainer: string;
  onPrimaryContainer: string;
  secondary: string;
  onSecondary: string;
  secondaryContainer: string;
  onSecondaryContainer: string;
  background: string;
  surface: string;
  surfaceContainerLowest: string;
  surfaceContainerLow: string;
  surfaceContainer: string;
  surfaceContainerHigh: string;
  surfaceContainerHighest: string;
  onSurface: string;
  onSurfaceVariant: string;
  outline: string;
  outlineVariant: string;
  error: string;
  onError: string;
  errorContainer: string;
  onErrorContainer: string;
}

const LIGHT: Palette = {
  dark: false,
  primary: '#2A78D6',
  onPrimary: '#FFFFFF',
  primaryContainer: '#D7E7FB',
  onPrimaryContainer: '#0B2B4F',
  secondary: '#52514E',
  onSecondary: '#FFFFFF',
  secondaryContainer: '#EAE8E1',
  onSecondaryContainer: '#2B2A26',
  background: '#F9F9F7',
  surface: '#FCFCFB',
  surfaceContainerLowest: '#FFFFFF',
  surfaceContainerLow: '#FCFCFB',
  surfaceContainer: '#F4F3EF',
  surfaceContainerHigh: '#EEEDE8',
  surfaceContainerHighest: '#E7E6E0',
  onSurface: '#0B0B0B',
  onSurfaceVariant: '#52514E',
  outline: '#C3C2B7',
  outlineVariant: '#E4E3DD',
  error: '#B3261E',
  onError: '#FFFFFF',
  errorContainer: '#F9DEDC',
  onErrorContainer: '#410E0B',
};

const DARK: Palette = {
  dark: true,
  primary: '#3987E5',
  onPrimary: '#06182E',
  primaryContainer: '#123B66',
  onPrimaryContainer: '#D7E7FB',
  secondary: '#C3C2B7',
  onSecondary: '#141412',
  secondaryContainer: '#3A3934',
  onSecondaryContainer: '#E7E5DE',
  background: '#0D0D0D',
  surface: '#1A1A19',
  surfaceContainerLowest: '#0D0D0D',
  surfaceContainerLow: '#1A1A19',
  surfaceContainer: '#201F1E',
  surfaceContainerHigh: '#262523',
  surfaceContainerHighest: '#2D2C2A',
  onSurface: '#F0EFEC',
  onSurfaceVariant: '#C3C2B7',
  outline: '#383835',
  outlineVariant: '#302F2C',
  error: '#F2B8B5',
  onError: '#601410',
  errorContainer: '#8C1D18',
  onErrorContainer: '#F9DEDC',
};

/** 图表配色，与 Android 的 ChartPalette 对应。 */
export interface ChartPalette {
  series1: string;
  series2: string;
  ink: string;
  secondary: string;
  muted: string;
  baseline: string;
}

export function chartPalette(dark: boolean): ChartPalette {
  if (dark) {
    return {
      series1: '#3987E5',
      series2: '#D95926',
      ink: '#F0EFEC',
      secondary: '#C3C2B7',
      muted: '#898781',
      baseline: '#383835',
    };
  }
  return {
    series1: '#2A78D6',
    series2: '#EB6834',
    ink: '#0B0B0B',
    secondary: '#52514E',
    muted: '#6E6C66',
    baseline: '#C3C2B7',
  };
}

export function paletteFor(dark: boolean): Palette {
  return dark ? DARK : LIGHT;
}

/**
 * 当前生效的主题模式。
 *
 * SYSTEM 需要跟随系统深浅色：这里用 preferences 里的模式 + 系统 colorMode 判断，
 * 由页面在 onConfigurationUpdate 时刷新。
 */
export class ThemeController {
  private static mode: ThemeMode = ThemeMode.SYSTEM;

  static load(): void {
    ThemeController.mode = AppPreferences.themeMode();
  }

  static current(): ThemeMode {
    return ThemeController.mode;
  }

  static set(mode: ThemeMode): void {
    ThemeController.mode = mode;
    AppPreferences.setThemeMode(mode);
  }

  /** 是否深色：显式模式优先，否则看系统。 */
  static isDark(systemDark: boolean): boolean {
    switch (ThemeController.mode) {
      case ThemeMode.LIGHT:
        return false;
      case ThemeMode.DARK:
        return true;
      default:
        return systemDark;
    }
  }
}
