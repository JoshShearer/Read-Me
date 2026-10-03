// Text in the palette colour of the system mode (REA-22, REA-24). Screens import this instead
// of React Native's Text, whose default colour is black in dark mode too. `tone` picks the
// Material 3 role; a colour in the caller's style still wins, so the highlight keeps
// onPrimaryContainer.
import React from 'react';
import { Text as RNText, useColorScheme, type TextProps } from 'react-native';
import { faint, palette, type Palette } from './theme';

export type Tone = 'body' | 'secondary' | 'action' | 'disabled' | 'error';

const COLOR: Record<Tone, (p: Palette) => string> = {
  body: p => p.onSurface,
  secondary: p => p.onSurfaceVariant,
  action: p => p.primary,
  disabled: faint,
  error: p => p.error,
};

export function Text({ style, tone = 'body', ...rest }: TextProps & { tone?: Tone }) {
  const p = palette(useColorScheme());
  return <RNText {...rest} style={[{ color: COLOR[tone](p) }, style]} />;
}
