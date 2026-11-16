/**
 * 事件类型可选颜色。数值为 ARGB，与 Android 版完全一致，
 * 保证同一份备份导入两端后颜色不变。
 */
export class EventColors {
  static readonly BLUE: number = 0xFF2A78D6;
  static readonly ORANGE: number = 0xFFEB6834;
  static readonly GREEN: number = 0xFF2E9E5B;
  static readonly PURPLE: number = 0xFF8B5CF6;
  static readonly RED: number = 0xFFD64545;
  static readonly TEAL: number = 0xFF0E9F9A;
  static readonly PINK: number = 0xFFEC4899;
  static readonly GOLD: number = 0xFFC89B1E;

  /** 未知事件类型的兜底色，不参与可选色板。 */
  static readonly SLATE: number = 0xFF6B7280;

  static palette(): number[] {
    return [
      EventColors.BLUE,
      EventColors.ORANGE,
      EventColors.GREEN,
      EventColors.PURPLE,
      EventColors.RED,
      EventColors.TEAL,
      EventColors.PINK,
      EventColors.GOLD,
    ];
  }
}

/** 0xAARRGGBB → '#AARRGGBB'，ArkUI 的颜色字符串。 */
export function colorString(argb: number): string {
  const value = (argb >>> 0).toString(16).padStart(8, '0').toUpperCase();
  return `#${value}`;
}

/** 只取 RGB 部分（丢弃 alpha），用于需要不透明底色的场景。 */
export function rgbColorString(argb: number): string {
  const value = (argb & 0xFFFFFF).toString(16).padStart(6, '0').toUpperCase();
  return `#${value}`;
}

/** 把颜色按比例混到白色/黑色背景上，模拟 Compose 里带 alpha 的叠加。 */
export function withAlpha(argb: number, alpha: number): string {
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
  return colorString(((a << 24) | (argb & 0xFFFFFF)) >>> 0);
}
