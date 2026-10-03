// R-M01 (REA-22): the app follows the system light or dark mode. The window background
// follows it natively (AppTheme is DayNight), but React Native draws text black in either
// mode, so every screen takes its colours from here. Contrast targets, checked by
// __tests__/theme.test.tsx and on the phone by `npm run device:themes`: 4.5:1 for text, 3:1
// for text drawn faint on purpose (a cut paragraph, a disabled control).
import type { ColorSchemeName } from 'react-native';

export type Palette = {
  bg: string;
  text: string;
  border: string;
  highlight: string;
  highlightText: string;
};

const LIGHT: Palette = {
  bg: '#fafafa',
  text: '#111111',
  border: '#b0b0b0',
  highlight: '#ffe680',
  highlightText: '#000000',
};

const DARK: Palette = {
  bg: '#121212',
  text: '#e6e6e6',
  border: '#5a5a5a',
  highlight: '#ffe680',
  highlightText: '#000000',
};

/** Opacity of secondary text (site, word count, state). */
export const MUTED = 0.7;
/** Opacity of a cut paragraph and a disabled control: faint, but still 3:1. */
export const FAINT = 0.5;

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
