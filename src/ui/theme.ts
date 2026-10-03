// R-M01 (REA-22, REA-24): the app follows the system light or dark mode. React Native draws
// text black in either mode, so every screen takes its colours from here. The colours are the
// LoopString web app's Material 3 tokens (its tailwind.config.cjs), so Read Me looks like the
// rest of LoopString. Contrast targets, checked by __tests__/theme.test.tsx and on the phone by
// `npm run device:themes`: 4.5:1 for text, 3:1 for text drawn faint on purpose (a cut
// paragraph, a disabled control). The window background behind the first frame is the same
// surface colour, from android/app/src/main/res/values*/colors.xml.
import type { ColorSchemeName } from 'react-native';

type Roles = {
  primary: string;
  onPrimary: string;
  primaryContainer: string;
  onPrimaryContainer: string;
  secondary: string;
  onSecondary: string;
  secondaryContainer: string;
  onSecondaryContainer: string;
  tertiary: string;
  error: string;
  onError: string;
  surface: string;
  onSurface: string;
  surfaceVariant: string;
  onSurfaceVariant: string;
  outline: string;
  outlineVariant: string;
  surfaceContainerLow: string;
  surfaceContainerHigh: string;
};

export type Palette = Roles & {
  // REA-22 names, kept as aliases of the roles above.
  bg: string;
  text: string;
  border: string;
  highlight: string;
  highlightText: string;
};

function withAliases(r: Roles): Palette {
  return {
    ...r,
    bg: r.surface,
    text: r.onSurface,
    border: r.outlineVariant,
    highlight: r.primaryContainer,
    highlightText: r.onPrimaryContainer,
  };
}

const LIGHT = withAliases({
  primary: '#7343bc',
  onPrimary: '#ffffff',
  primaryContainer: '#ecdcff',
  onPrimaryContainer: '#420099',
  secondary: '#645b70',
  onSecondary: '#ffffff',
  secondaryContainer: '#e8ddf8',
  onSecondaryContainer: '#1e192b',
  tertiary: '#7f525c',
  error: '#ba1a1a',
  onError: '#ffffff',
  surface: '#fffbff',
  onSurface: '#1d1b1e',
  surfaceVariant: '#e9dfeb',
  onSurfaceVariant: '#4a444e',
  outline: '#7c757e',
  outlineVariant: '#ccc4ce',
  surfaceContainerLow: '#f7f2fc',
  surfaceContainerHigh: '#ede7f7',
});

const DARK = withAliases({
  primary: '#d5baff',
  onPrimary: '#3e008d',
  primaryContainer: '#5a27a3',
  onPrimaryContainer: '#ecdcff',
  secondary: '#ccc2db',
  onSecondary: '#322b3d',
  secondaryContainer: '#484054',
  onSecondaryContainer: '#e8ddf8',
  tertiary: '#f0b7c4',
  error: '#ffb4ab',
  onError: '#690005',
  surface: '#1d1b1e',
  onSurface: '#e6e1e6',
  surfaceVariant: '#4a444e',
  onSurfaceVariant: '#ccc4ce',
  outline: '#968e98',
  outlineVariant: '#4a444e',
  surfaceContainerLow: '#262228',
  surfaceContainerHigh: '#342e38',
});

/**
 * How far a cut paragraph or a disabled control is blended from onSurface toward the surface:
 * faint, but still 3:1. Primary at this strength is 2.26:1 on the light surface, so disabled
 * controls fade from onSurface, not from primary.
 */
export const FAINT = 0.5;

/** The web app's type scale (Tailwind xs..2xl, rem at 16 px), in dp. System sans, no font asset. */
export const type = {
  xs: { fontSize: 12, lineHeight: 16 },
  sm: { fontSize: 14, lineHeight: 20 },
  base: { fontSize: 16, lineHeight: 24 },
  lg: { fontSize: 18, lineHeight: 28 },
  xl: { fontSize: 20, lineHeight: 28 },
  '2xl': { fontSize: 24, lineHeight: 32 },
} as const;

export function palette(scheme: ColorSchemeName | null | undefined): Palette {
  return scheme === 'dark' ? DARK : LIGHT;
}

export function statusBarStyle(scheme: ColorSchemeName | null | undefined): 'dark-content' | 'light-content' {
  return scheme === 'dark' ? 'light-content' : 'dark-content';
}

function rgb(hex: string): [number, number, number] {
  return [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number];
}

/** [fg] drawn at [opacity] over [bg], as a hex colour. */
export function blend(fg: string, bg: string, opacity: number): string {
  const f = rgb(fg);
  const b = rgb(bg);
  const c = f.map((v, i) => Math.round(v * opacity + b[i] * (1 - opacity)));
  return '#' + c.map(v => v.toString(16).padStart(2, '0')).join('');
}

/** The colour of a cut paragraph or a disabled control. */
export function faint(p: Palette): string {
  return blend(p.onSurface, p.surface, FAINT);
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map(v => {
    const c = v / 255;
    return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2 contrast ratio of two hex colours. */
export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
