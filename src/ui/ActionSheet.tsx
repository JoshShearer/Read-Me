// REA-62: a bottom sheet of actions, in the style of Select.tsx (Modal, scrim, Material 3
// surface, theme tones only). It replaces Alert.alert for Trim's long press: One UI drew the
// platform dialog with a large empty message area between its title and its stacked buttons,
// and the app's own sheet does not depend on how a vendor renders it.
//
// The Modal element is always in the screen's JSX and switched by `visible`. React Native's
// Modal renders nothing while hidden, so opening it does mount a native modal host, as the
// screen View's last child (as Select's does, which the phone has run since REA-28); every View
// is collapsable={false} (react/react-native#58265).
// Back and a tap on the scrim close it without an action; so does the Cancel row.
//
// Rows have a 48 dp minimum, not a fixed height: at 360 dp and a large font scale a label can
// wrap, and a fixed row would clip its second line. The label keeps the font scale Android asks
// for (no maxFontSizeMultiplier, R-M01).
import React from 'react';
import { Modal, Pressable, StyleSheet, useColorScheme, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Text } from './Text';
import { palette, type } from './theme';

export type SheetAction = { label: string; testID: string; onPress: () => void };

// Material's minimum touch target.
const ROW = 48;

export function ActionSheet({
  visible,
  title,
  actions,
  cancelLabel = 'Cancel',
  cancelTestID,
  onClose,
}: {
  visible: boolean;
  title: string;
  actions: readonly SheetAction[];
  cancelLabel?: string;
  cancelTestID: string;
  onClose: () => void;
}) {
  const p = palette(useColorScheme());
  const insets = useSafeAreaInsets();
  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      navigationBarTranslucent
      onRequestClose={onClose}>
      <View collapsable={false} style={s.backdrop}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`close ${title.toLowerCase()} menu`}
          onPress={onClose}
          style={[StyleSheet.absoluteFill, { backgroundColor: p.scrim }]}
        />
        <View
          collapsable={false}
          style={[s.sheet, { backgroundColor: p.surfaceContainerLow, paddingBottom: insets.bottom + 8 }]}>
          <View collapsable={false} style={[s.handle, { backgroundColor: p.outline }]} />
          <Text accessibilityRole="header" style={s.title}>{title}</Text>
          {[...actions, { label: cancelLabel, testID: cancelTestID, onPress: onClose }].map(a => (
            <Pressable
              key={a.testID}
              testID={a.testID}
              accessibilityRole="button"
              accessibilityLabel={a.label}
              onPress={a.onPress}
              style={({ pressed }) => [s.row, pressed ? { backgroundColor: p.surfaceContainerHigh } : null]}>
              <Text style={s.label}>{a.label}</Text>
            </Pressable>
          ))}
        </View>
      </View>
    </Modal>
  );
}

const s = StyleSheet.create({
  backdrop: { flex: 1, justifyContent: 'flex-end' },
  sheet: { borderTopLeftRadius: 28, borderTopRightRadius: 28, paddingTop: 8 },
  handle: { alignSelf: 'center', width: 32, height: 4, borderRadius: 2, marginBottom: 8 },
  title: { ...type.lg, fontWeight: '600', paddingHorizontal: 24, paddingVertical: 12 },
  row: { minHeight: ROW, paddingVertical: 12, justifyContent: 'center', marginHorizontal: 12, paddingHorizontal: 16, borderRadius: 16 },
  label: { ...type.base, fontWeight: '500' },
});
