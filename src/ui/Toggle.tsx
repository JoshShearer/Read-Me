// REA-28: an on/off row modeled on the web app's Toggle, as a Material 3 switch. The whole row
// is the target and carries role switch with a checked state, so TalkBack reads "Obsidian
// bridge, switch, on" and the device scripts read checked= from the dump instead of a label
// that changes with the state.
//
// The track and thumb are graphics, so R-M01's text ratios do not apply to them; WCAG 1.4.11
// asks 3:1 against the surface, which primary (on) and outline (off) meet in both modes
// (__tests__/controls.test.tsx).
import React from 'react';
import { Pressable, StyleSheet, useColorScheme, View } from 'react-native';
import { Text } from './Text';
import { palette, type } from './theme';

export function Toggle({
  label,
  value,
  onValueChange,
  disabled = false,
}: {
  label: string;
  value: boolean;
  onValueChange: (next: boolean) => void;
  disabled?: boolean;
}) {
  const p = palette(useColorScheme());
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={label}
      accessibilityState={{ checked: value, disabled }}
      disabled={disabled}
      onPress={() => onValueChange(!value)}
      style={s.row}>
      <Text tone={disabled ? 'disabled' : 'body'} style={s.label}>
        {label}
      </Text>
      <View
        collapsable={false}
        style={[
          s.track,
          value
            ? [s.on, { backgroundColor: p.primary, borderColor: p.primary }]
            : [s.off, { backgroundColor: p.surfaceContainerHigh, borderColor: p.outline }],
        ]}>
        <View
          collapsable={false}
          style={[value ? s.thumbOn : s.thumbOff, { backgroundColor: value ? p.onPrimary : p.outline }]}
        />
      </View>
    </Pressable>
  );
}

const s = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', minHeight: 48, gap: 16 },
  label: { ...type.base, flex: 1 },
  track: { width: 52, height: 32, borderRadius: 16, borderWidth: 2, justifyContent: 'center', paddingHorizontal: 4 },
  on: { alignItems: 'flex-end' },
  off: { alignItems: 'flex-start' },
  thumbOn: { width: 24, height: 24, borderRadius: 12, marginRight: -2 },
  thumbOff: { width: 16, height: 16, borderRadius: 8, marginLeft: 2 },
});
