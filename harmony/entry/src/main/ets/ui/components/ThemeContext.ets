import { Palette, chartPalette, ChartPalette, paletteFor } from '../../common/Palette';

/**
 * 主题上下文。
 *
 * ArkUI 没有 Compose 的 CompositionLocal，这里用 AppStorage 共享当前调色板：
 * 根页面切换深浅色时写一次，所有子组件通过 @StorageProp 自动刷新。
 */
export const KEY_PALETTE_DARK = 'locker_palette_dark';

export function setPaletteDark(dark: boolean): void {
  AppStorage.setOrCreate<boolean>(KEY_PALETTE_DARK, dark);
}

export function isPaletteDark(): boolean {
  const value = AppStorage.get<boolean>(KEY_PALETTE_DARK);
  return value === undefined ? false : value;
}

/** 当前调色板。 */
export function currentPalette(): Palette {
  return paletteFor(isPaletteDark());
}

/** 当前图表配色。 */
export function currentChartPalette(): ChartPalette {
  return chartPalette(isPaletteDark());
}
