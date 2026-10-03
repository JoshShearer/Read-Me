// Text in the palette colour of the system mode (REA-22). Screens import this instead of
// React Native's Text, whose default colour is black in dark mode too. A colour in the
// caller's style wins, so the highlight keeps its black text.
import React from 'react';
import { Text as RNText, useColorScheme, type TextProps } from 'react-native';
import { palette } from './theme';

export function Text({ style, ...rest }: TextProps) {
  const p = palette(useColorScheme());
  return <RNText {...rest} style={[{ color: p.text }, style]} />;
}
