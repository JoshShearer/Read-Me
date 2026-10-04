// REA-28: a dropdown modeled on the web app's Select. The web one is a DOM <select>; here the
// field opens a bottom sheet (a Modal) listing the options, Material 3 style. Back and a tap
// on the scrim close it without a change.
//
// The Modal element is always rendered and switched by `visible`, so opening the sheet never
// adds or removes a sibling in the screen's tree; every View is collapsable={false}
// (react/react-native#58265, see ui.ts).
//
// Accessibility: the field is a combobox whose label is the setting's name and whose value is
// the current option (Android reads both, and uiautomator dumps them as "<name>, <option>");
// each option is a radio with a checked state, labelled "<name> <value>" so the device
// scripts can find one by its value.
//
// The field is a Material 3 filled field: its own small label ("Engine", "Voice") over the
// current option and that option's detail, on a tonal surface, so two fields under one section
// heading read as two settings. An option can be disabled (an engine Android will not bind):
// it stays listed, faint, with its reason as the detail, and cannot be picked.
import React, { useState } from 'react';
import { FlatList, Modal, Pressable, StyleSheet, useColorScheme, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Text } from './Text';
import { palette, type } from './theme';

export type Option = { value: string; label: string; detail?: string; disabled?: boolean };

// Every option row is this tall, so the list can open scrolled to the current option.
const ROW = 64;

export function Select({
  name,
  title,
  options,
  value,
  placeholder = 'Choose',
  disabled = false,
  onChange,
}: {
  /** Lower-case accessibility name; also the prefix of each option's label. */
  name: string;
  /** The field's label and the heading of the sheet. */
  title: string;
  options: readonly Option[];
  value: string | null;
  placeholder?: string;
  /** While its options load: the field shows the placeholder, faint, and does not open. */
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const p = palette(useColorScheme());
  const insets = useSafeAreaInsets();
  const [open, setOpen] = useState(false);
  const at = options.findIndex(o => o.value === value);
  const current = at >= 0 ? options[at] : null;

  const pick = (v: string) => {
    setOpen(false);
    if (v !== value) onChange(v);
  };

  return (
    <View collapsable={false}>
      <Pressable
        accessibilityRole="combobox"
        accessibilityLabel={name}
        // No value while disabled: the device scripts wait for "voice, <name>" to know it is ready.
        accessibilityValue={disabled ? undefined : { text: current?.label ?? placeholder }}
        accessibilityState={{ expanded: open, disabled }}
        disabled={disabled}
        onPress={() => setOpen(true)}
        style={({ pressed }) => [
          s.field,
          { backgroundColor: pressed ? p.surfaceContainerHigh : p.surfaceContainerLow, borderColor: open ? p.primary : p.outlineVariant },
        ]}>
        <View collapsable={false} style={s.fieldText}>
          <Text tone="secondary" style={s.label}>{title}</Text>
          <Text tone={disabled ? 'secondary' : 'body'} numberOfLines={1} style={s.value}>
            {current?.label ?? placeholder}
          </Text>
          {current?.detail ? <Text tone="secondary" numberOfLines={1} style={s.small}>{current.detail}</Text> : null}
        </View>
        <Text tone="secondary" style={s.chevron}>{'▾︎'}</Text>
      </Pressable>
      <Modal
        visible={open}
        transparent
        animationType="fade"
        statusBarTranslucent
        navigationBarTranslucent
        onRequestClose={() => setOpen(false)}>
        <View collapsable={false} style={s.backdrop}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`close ${name}`}
            onPress={() => setOpen(false)}
            style={[StyleSheet.absoluteFill, { backgroundColor: p.scrim }]}
          />
          <View
            collapsable={false}
            style={[s.sheet, { backgroundColor: p.surfaceContainerLow, paddingBottom: insets.bottom + 8 }]}>
            <View collapsable={false} style={[s.handle, { backgroundColor: p.outline }]} />
            <Text accessibilityRole="header" style={s.title}>{title}</Text>
            <FlatList
              data={options}
              keyExtractor={o => o.value}
              initialScrollIndex={at > 0 ? at : undefined}
              getItemLayout={(_, i) => ({ length: ROW, offset: ROW * i, index: i })}
              renderItem={({ item: o }) => {
                const on = o.value === value;
                const ink = on ? { color: p.onSecondaryContainer } : null;
                return (
                  <Pressable
                    accessibilityRole="radio"
                    accessibilityLabel={`${name} ${o.value}`}
                    accessibilityState={o.disabled ? { checked: on, disabled: true } : { checked: on }}
                    disabled={o.disabled}
                    onPress={() => pick(o.value)}
                    style={({ pressed }) => [
                      s.option,
                      on ? { backgroundColor: p.secondaryContainer } : pressed ? { backgroundColor: p.surfaceContainerHigh } : null,
                    ]}>
                    <View
                      collapsable={false}
                      style={[s.radio, { borderColor: on ? p.onSecondaryContainer : o.disabled ? p.outlineVariant : p.outline }]}>
                      {on ? <View collapsable={false} style={[s.dot, { backgroundColor: p.onSecondaryContainer }]} /> : null}
                    </View>
                    <View collapsable={false} style={s.fieldText}>
                      <Text numberOfLines={1} tone={o.disabled ? 'disabled' : 'body'} style={[s.value, ink]}>
                        {o.label}
                      </Text>
                      {o.detail ? (
                        <Text numberOfLines={1} tone={o.disabled ? 'disabled' : 'secondary'} style={[s.small, ink]}>
                          {o.detail}
                        </Text>
                      ) : null}
                    </View>
                  </Pressable>
                );
              }}
            />
          </View>
        </View>
      </Modal>
    </View>
  );
}

const s = StyleSheet.create({
  field: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 64,
    paddingLeft: 16,
    paddingRight: 12,
    paddingVertical: 10,
    borderWidth: 1,
    borderRadius: 16,
    gap: 12,
  },
  fieldText: { flex: 1 },
  label: { ...type.xs, letterSpacing: 0.4 },
  value: { ...type.base, fontWeight: '500' },
  small: type.sm,
  chevron: type.lg,
  backdrop: { flex: 1, justifyContent: 'flex-end' },
  sheet: { maxHeight: '70%', borderTopLeftRadius: 28, borderTopRightRadius: 28, paddingTop: 8 },
  handle: { alignSelf: 'center', width: 32, height: 4, borderRadius: 2, marginBottom: 8 },
  title: { ...type.lg, fontWeight: '600', paddingHorizontal: 24, paddingVertical: 12 },
  option: {
    height: ROW,
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 12,
    paddingHorizontal: 16,
    borderRadius: 16,
    gap: 16,
  },
  radio: { width: 20, height: 20, borderRadius: 10, borderWidth: 2, alignItems: 'center', justifyContent: 'center' },
  dot: { width: 10, height: 10, borderRadius: 5 },
});
