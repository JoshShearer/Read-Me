import React from 'react';
import { StyleSheet, Text as RNText } from 'react-native';
import * as RN from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import { blend, contrast, FAINT, MUTED, palette, statusBarStyle } from '../src/ui/theme';
import { Text } from '../src/ui/Text';

// REA-22: React Native's default text colour is black whatever the system mode, and the
// window background follows it, so dark mode drew black on dark grey.
describe.each(['light', 'dark'] as const)('%s palette', scheme => {
  const p = palette(scheme);

  test('body text reads at 4.5:1 or better', () => {
    expect(contrast(p.text, p.bg)).toBeGreaterThanOrEqual(4.5);
  });

  test('secondary text (MUTED) reads at 4.5:1 or better', () => {
    expect(contrast(blend(p.text, p.bg, MUTED), p.bg)).toBeGreaterThanOrEqual(4.5);
  });

  test('cut paragraphs and disabled controls (FAINT) read at 3:1 or better', () => {
    expect(contrast(blend(p.text, p.bg, FAINT), p.bg)).toBeGreaterThanOrEqual(3);
  });

  test('the sentence highlight reads at 4.5:1 or better', () => {
    expect(contrast(p.highlightText, p.highlight)).toBeGreaterThanOrEqual(4.5);
  });

  test('borders are visible at 1.5:1 or better', () => {
    expect(contrast(p.border, p.bg)).toBeGreaterThanOrEqual(1.5);
  });
});

test('status bar icons are dark on the light background and light on the dark one', () => {
  expect(statusBarStyle('light')).toBe('dark-content');
  expect(statusBarStyle('dark')).toBe('light-content');
});

test('an unknown scheme is treated as light', () => {
  expect(palette(null)).toEqual(palette('light'));
});

describe('Text', () => {
  afterEach(() => jest.restoreAllMocks());

  function colorOf(scheme: 'light' | 'dark', style?: object) {
    jest.spyOn(RN, 'useColorScheme').mockReturnValue(scheme);
    let r!: ReactTestRenderer.ReactTestRenderer;
    ReactTestRenderer.act(() => {
      r = ReactTestRenderer.create(<Text style={style}>x</Text>);
    });
    return StyleSheet.flatten(r.root.findByType(RNText).props.style).color;
  }

  test('takes the palette text colour of the system mode', () => {
    expect(colorOf('dark')).toBe(palette('dark').text);
    expect(colorOf('light')).toBe(palette('light').text);
  });

  test('a colour in the caller style wins (the highlight keeps black text)', () => {
    expect(colorOf('dark', { color: '#000' })).toBe('#000');
  });
});

describe('screens', () => {
  const fs = require('fs') as typeof import('fs');
  const path = require('path') as typeof import('path');
  const dir = path.join(__dirname, '..', 'src', 'ui');
  const screens = fs.readdirSync(dir).filter(f => f.endsWith('Screen.tsx'));

  test.each(screens)('%s takes Text from ./Text, not react-native', f => {
    const src = fs.readFileSync(path.join(dir, f), 'utf8');
    const rn = src.match(/import \{([^}]*)\} from 'react-native'/);
    expect(rn?.[1].split(',').map(s => s.trim())).not.toContain('Text');
    expect(src).toMatch(/import \{ Text \} from '\.\/Text'/);
  });

  test('faint styles use FAINT and MUTED', () => {
    const { ui } = require('../src/ui/ui');
    expect(ui.cut.opacity).toBe(FAINT);
    expect(ui.disabled.opacity).toBe(FAINT);
    expect(ui.small.opacity).toBe(MUTED);
  });
});
