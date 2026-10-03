import React from 'react';
import { StyleSheet, Text as RNText } from 'react-native';
import * as RN from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import { blend, contrast, FAINT, palette, statusBarStyle, type } from '../src/ui/theme';
import { Text } from '../src/ui/Text';

// REA-24: the LoopString web app's Material 3 tokens (/data/Dev/LoopString/tailwind.config.cjs),
// exactly as the issue lists them.
const M3 = {
  light: {
    primary: '#7343bc',
    onPrimary: '#ffffff',
    primaryContainer: '#ecdcff',
    onPrimaryContainer: '#420099',
    error: '#ba1a1a',
    surface: '#fffbff',
    onSurface: '#1d1b1e',
    onSurfaceVariant: '#4a444e',
    outlineVariant: '#ccc4ce',
    surfaceContainerLow: '#f7f2fc',
    surfaceContainerHigh: '#ede7f7',
  },
  dark: {
    primary: '#d5baff',
    onPrimary: '#3e008d',
    primaryContainer: '#5a27a3',
    onPrimaryContainer: '#ecdcff',
    error: '#ffb4ab',
    surface: '#1d1b1e',
    onSurface: '#e6e1e6',
    onSurfaceVariant: '#ccc4ce',
    outlineVariant: '#4a444e',
    surfaceContainerLow: '#262228',
    surfaceContainerHigh: '#342e38',
  },
};

// REA-22: React Native's default text colour is black whatever the system mode, and the
// window background follows it, so dark mode drew black on dark grey.
describe.each(['light', 'dark'] as const)('%s palette', scheme => {
  const p = palette(scheme);

  test('carries the Material 3 roles of the LoopString web app', () => {
    expect(p).toMatchObject(M3[scheme]);
  });

  test('the REA-22 names alias the Material 3 roles', () => {
    expect(p.bg).toBe(p.surface);
    expect(p.text).toBe(p.onSurface);
    expect(p.border).toBe(p.outlineVariant);
    expect(p.highlight).toBe(p.primaryContainer);
    expect(p.highlightText).toBe(p.onPrimaryContainer);
  });

  // Text sits on the screen (surface) or on a card (surfaceContainerLow).
  test.each(['surface', 'surfaceContainerLow'] as const)('text on %s reads at 4.5:1 or better', bg => {
    expect(contrast(p.onSurface, p[bg])).toBeGreaterThanOrEqual(4.5);
    expect(contrast(p.onSurfaceVariant, p[bg])).toBeGreaterThanOrEqual(4.5);
    expect(contrast(p.primary, p[bg])).toBeGreaterThanOrEqual(4.5);
    expect(contrast(p.error, p[bg])).toBeGreaterThanOrEqual(4.5);
  });

  test('cut paragraphs and disabled controls (onSurface at FAINT) read at 3:1 or better', () => {
    expect(contrast(blend(p.onSurface, p.surface, FAINT), p.surface)).toBeGreaterThanOrEqual(3);
  });

  test('the sentence highlight is primaryContainer and reads at 4.5:1 or better', () => {
    expect(p.highlight).toBe(p.primaryContainer);
    expect(contrast(p.onPrimaryContainer, p.primaryContainer)).toBeGreaterThanOrEqual(4.5);
  });

  test('borders are visible at 1.5:1 or better', () => {
    expect(contrast(p.outlineVariant, p.surface)).toBeGreaterThanOrEqual(1.5);
  });
});

test('the highlight is picked per mode, not one colour for both', () => {
  expect(palette('dark').primaryContainer).not.toBe(palette('light').primaryContainer);
});

test('status bar icons are dark on the light background and light on the dark one', () => {
  expect(statusBarStyle('light')).toBe('dark-content');
  expect(statusBarStyle('dark')).toBe('light-content');
});

test('an unknown scheme is treated as light', () => {
  expect(palette(null)).toEqual(palette('light'));
});

test('the type scale is the web app one (rem at 16 px)', () => {
  expect(type).toEqual({
    xs: { fontSize: 12, lineHeight: 16 },
    sm: { fontSize: 14, lineHeight: 20 },
    base: { fontSize: 16, lineHeight: 24 },
    lg: { fontSize: 18, lineHeight: 28 },
    xl: { fontSize: 20, lineHeight: 28 },
    '2xl': { fontSize: 24, lineHeight: 32 },
  });
});

describe('Text', () => {
  afterEach(() => jest.restoreAllMocks());

  function styleOf(scheme: 'light' | 'dark', props: object = {}) {
    jest.spyOn(RN, 'useColorScheme').mockReturnValue(scheme);
    let r!: ReactTestRenderer.ReactTestRenderer;
    ReactTestRenderer.act(() => {
      r = ReactTestRenderer.create(<Text {...props}>x</Text>);
    });
    return StyleSheet.flatten(r.root.findByType(RNText).props.style);
  }

  test.each(['light', 'dark'] as const)('body text is onSurface in %s mode', scheme => {
    expect(styleOf(scheme).color).toBe(palette(scheme).onSurface);
  });

  test.each(['light', 'dark'] as const)('each tone takes its role in %s mode', scheme => {
    const p = palette(scheme);
    expect(styleOf(scheme, { tone: 'body' }).color).toBe(p.onSurface);
    expect(styleOf(scheme, { tone: 'secondary' }).color).toBe(p.onSurfaceVariant);
    expect(styleOf(scheme, { tone: 'action' }).color).toBe(p.primary);
    expect(styleOf(scheme, { tone: 'heading' }).color).toBe(p.primary);
    expect(styleOf(scheme, { tone: 'error' }).color).toBe(p.error);
    // Not primary at FAINT: that is 2.26:1 on the light surface, below 3:1.
    expect(styleOf(scheme, { tone: 'disabled' }).color).toBe(blend(p.onSurface, p.surface, FAINT));
  });

  test('a colour in the caller style wins (the highlight keeps onPrimaryContainer)', () => {
    const c = palette('dark').onPrimaryContainer;
    expect(styleOf('dark', { tone: 'action', style: { color: c } }).color).toBe(c);
  });
});

describe('screens', () => {
  const fs = require('fs') as typeof import('fs');
  const path = require('path') as typeof import('path');
  const dir = path.join(__dirname, '..', 'src', 'ui');
  const screens = fs.readdirSync(dir).filter(f => f.endsWith('Screen.tsx'));
  // REA-28: the shared controls draw text and colour too.
  const controls = ['Button.tsx', 'Select.tsx', 'Stepper.tsx', 'Toggle.tsx'];
  const read = (f: string) => fs.readFileSync(path.join(dir, f), 'utf8');

  test.each([...screens, ...controls])('%s takes Text from ./Text, not react-native', f => {
    const src = read(f);
    const rn = src.match(/import \{([^}]*)\} from 'react-native'/);
    expect(rn?.[1].split(',').map(s => s.trim())).not.toContain('Text');
    expect(src).toMatch(/import \{ Text(, type Tone)? \} from '\.\/Text'/);
  });

  test.each([...[...screens, ...controls].map(f => path.join(dir, f)), path.join(dir, 'ui.ts'), path.join(dir, 'Text.tsx'), path.join(__dirname, '..', 'App.tsx')])(
    'no colour literal outside theme.ts: %s',
    f => {
      const src = fs.readFileSync(f, 'utf8');
      expect(src).not.toMatch(/['"`]#[0-9a-fA-F]{3,8}\b/);
      expect(src).not.toMatch(/rgba?\(/);
    },
  );

  // Every Text that is a button label or secondary text names its tone, so buttons are primary
  // and secondary text is onSurfaceVariant rather than the body colour.
  test.each(screens)('%s gives small text a tone and draws buttons with ./Button', f => {
    const src = read(f);
    const tags = src.match(/<Text\b[^>]*>/g) ?? [];
    for (const t of tags.filter(x => /ui\.small\b/.test(x))) expect(t).toMatch(/\btone=/);
    // REA-28: a Text in the action tone is a hand-made button; Button draws those.
    expect(src).not.toMatch(/tone="action"/);
  });

  test('ui.ts is layout only: no underline, no opacity, no highlight colour', () => {
    const { ui } = require('../src/ui/ui');
    expect(ui.buttonText).toBeUndefined();
    // Colour alone carries faint and secondary text; opacity on top would halve the contrast
    // the tone already sets.
    expect(ui.small.opacity).toBeUndefined();
    expect(ui.cut.opacity).toBeUndefined();
    expect(ui.disabled?.opacity).toBeUndefined();
    expect(ui.cut.textDecorationLine).toBe('line-through');
    expect(ui.highlight).toBeUndefined();
  });
});

// REA-24: the native window draws its surface before React Native's first frame; if it drifts
// from theme.ts, launch and rotation flash a different colour.
test.each([
  ['light', 'values'],
  ['dark', 'values-night'],
] as const)('the %s window background is the theme surface', (scheme, dir) => {
  const fs = require('fs') as typeof import('fs');
  const path = require('path') as typeof import('path');
  const xml = fs.readFileSync(path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'res', dir, 'colors.xml'), 'utf8');
  expect(xml.match(/<color name="readme_surface">(#[0-9a-f]{6})<\/color>/)?.[1]).toBe(palette(scheme).surface);
  expect(xml).toMatch(new RegExp(`<bool name="light_system_bars">${scheme === 'light'}</bool>`));
});
