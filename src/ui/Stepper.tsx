// REA-28: − value + for the reading rate, on the Reader and in Settings. A stepper, not a
// slider: React Native core has no slider, and a hand-drawn one is harder to use with TalkBack
// than two buttons. Each end is disabled at its limit rather than doing nothing.
import React from 'react';
import { StyleSheet, View } from 'react-native';
import { IconButton } from './Button';
import { Text } from './Text';
import { type } from './theme';

export function Stepper({
  value,
  min,
  max,
  format,
  onStep,
  decreaseLabel,
  increaseLabel,
}: {
  value: number;
  min: number;
  max: number;
  format: (v: number) => string;
  onStep: (dir: 1 | -1) => void;
  decreaseLabel: string;
  increaseLabel: string;
}) {
  return (
    <View collapsable={false} style={s.row}>
      <IconButton glyph="−" accessibilityLabel={decreaseLabel} disabled={value <= min} onPress={() => onStep(-1)} />
      <Text style={s.value}>{format(value)}</Text>
      <IconButton glyph="+" accessibilityLabel={increaseLabel} disabled={value >= max} onPress={() => onStep(1)} />
    </View>
  );
}

const s = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center' },
  // Wide enough for "4.0x", so the buttons do not move as the value changes.
  value: { ...type.base, minWidth: 44, textAlign: 'center', fontVariant: ['tabular-nums'] },
});
