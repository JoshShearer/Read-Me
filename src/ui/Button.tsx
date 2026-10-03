// REA-28: buttons modeled on the LoopString web app's UI (ShroomSpy's Button and IconButton),
// rebuilt on Pressable because the web ones are DOM and Tailwind. Material 3 shapes: a 40 dp
// pill with a 48 dp touch target.
//
// A disabled button drops its fill and border and draws its label faint on the screen surface:
// faint on surfaceContainerHigh is 2.78:1 in light mode, below R-M01's 3:1, where faint on the
// surface is 3.28:1 (__tests__/controls.test.tsx).
import React from 'react';
import { Pressable, StyleSheet, useColorScheme, type StyleProp, type ViewStyle } from 'react-native';
import { Text, type Tone } from './Text';
import { palette, type, type Palette } from './theme';

export type Appearance = 'text' | 'filled' | 'tonal' | 'outlined';

type Look = { background?: string; border?: string; label: string; tone: Tone };

/** The colours of a button in one mode. Exported for the contrast tests. */
export function look(p: Palette, appearance: Appearance, disabled: boolean, destructive: boolean): Look {
  if (disabled) return { tone: 'disabled', label: '' };
  const tone: Tone = destructive ? 'error' : 'action';
  switch (appearance) {
    case 'filled':
      return { background: p.primary, label: p.onPrimary, tone };
    case 'tonal':
      return { background: p.secondaryContainer, label: p.onSecondaryContainer, tone };
    case 'outlined':
      return { border: destructive ? p.error : p.outline, label: '', tone };
    default:
      return { label: '', tone };
  }
}

// Vertical slop that grows the 40 dp pill to a 48 dp touch target.
const SLOP = { top: 4, bottom: 4 };

export function Button({
  label,
  onPress,
  appearance = 'text',
  disabled = false,
  destructive = false,
  accessibilityLabel,
  style,
}: {
  label: string;
  onPress: () => void;
  appearance?: Appearance;
  disabled?: boolean;
  /** Error colour, for text and outlined buttons that delete something. */
  destructive?: boolean;
  /** Defaults to the label; the device scripts find buttons by it. */
  accessibilityLabel?: string;
  style?: StyleProp<ViewStyle>;
}) {
  const l = look(palette(useColorScheme()), appearance, disabled, destructive);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      hitSlop={SLOP}
      onPress={onPress}
      style={[
        s.button,
        appearance === 'text' ? s.textButton : null,
        l.border ? s.bordered : null,
        { backgroundColor: l.background, borderColor: l.border },
        style,
      ]}>
      <Text tone={l.tone} style={[s.label, l.label ? { color: l.label } : null]}>
        {label}
      </Text>
    </Pressable>
  );
}

/**
 * A glyph button. The label is required: TalkBack and the device scripts cannot read a glyph.
 * U+FE0E asks for the text form, so Android does not draw a triangle as an emoji.
 */
export function IconButton({
  glyph,
  onPress,
  accessibilityLabel,
  disabled = false,
}: {
  glyph: string;
  onPress: () => void;
  accessibilityLabel: string;
  disabled?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={s.icon}>
      <Text tone={disabled ? 'disabled' : 'action'} style={s.glyph}>
        {[...glyph].map(c => `${c}︎`).join('')}
      </Text>
    </Pressable>
  );
}

const s = StyleSheet.create({
  button: {
    minHeight: 40,
    paddingHorizontal: 24,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
  },
  textButton: { paddingHorizontal: 12 },
  bordered: { borderWidth: 1 },
  label: { ...type.sm, fontWeight: '500' },
  icon: { width: 48, height: 48, borderRadius: 24, alignItems: 'center', justifyContent: 'center' },
  glyph: { ...type.xl, fontWeight: '500' },
});
