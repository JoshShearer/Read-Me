// REA-28: the shared controls. Contrast of every colour pair they draw, in both modes, and the
// accessibility role and state TalkBack and the device scripts read.
import React from 'react';
import * as RN from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import '../jest/loadReactNative';
import { Button, IconButton, look, type Appearance } from '../src/ui/Button';
import { Select, selectOffset } from '../src/ui/Select';
import { Stepper } from '../src/ui/Stepper';
import { Toggle } from '../src/ui/Toggle';
import { contrast, faint, palette } from '../src/ui/theme';

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

const TONE = (p: ReturnType<typeof palette>) => ({ action: p.primary, secondary: p.onSurfaceVariant, error: p.error, disabled: faint(p), body: p.onSurface });

describe.each(['light', 'dark'] as const)('%s mode', scheme => {
  const p = palette(scheme);

  test.each(['text', 'quiet', 'filled', 'tonal', 'outlined'] as Appearance[])('a %s button label reads at 4.5:1 or better', a => {
    for (const destructive of [false, true]) {
      const l = look(p, a, false, destructive);
      const fg = l.label || TONE(p)[l.tone as 'action' | 'error' | 'secondary'];
      expect(contrast(fg, l.background ?? p.surface)).toBeGreaterThanOrEqual(4.5);
      // On a card too.
      if (!l.background) expect(contrast(fg, p.surfaceContainerLow)).toBeGreaterThanOrEqual(4.5);
    }
  });

  test('a disabled button has no fill and its label reads at 3:1 on the surface', () => {
    for (const a of ['text', 'filled', 'tonal', 'outlined'] as Appearance[]) {
      expect(look(p, a, true, false)).toEqual({ tone: 'disabled', label: '' });
    }
    expect(contrast(faint(p), p.surface)).toBeGreaterThanOrEqual(3);
  });

  test('outlines, the switch track and thumb are 3:1 against the surface (WCAG 1.4.11)', () => {
    expect(contrast(p.outline, p.surface)).toBeGreaterThanOrEqual(3);
    expect(contrast(p.outline, p.surfaceContainerLow)).toBeGreaterThanOrEqual(3);
    expect(contrast(p.primary, p.surface)).toBeGreaterThanOrEqual(3);
    expect(contrast(p.onPrimary, p.primary)).toBeGreaterThanOrEqual(3);
  });

  test('the sheet and its selected option read at 4.5:1 or better', () => {
    expect(contrast(p.onSurface, p.surfaceContainerLow)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(p.onSurfaceVariant, p.surfaceContainerLow)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(p.onSecondaryContainer, p.secondaryContainer)).toBeGreaterThanOrEqual(4.5);
  });
});

const mounted: ReactTestRenderer.ReactTestRenderer[] = [];
afterEach(() => {
  ReactTestRenderer.act(() => {
    while (mounted.length) mounted.pop()!.unmount();
  });
});
function render(el: React.ReactElement) {
  let r!: ReactTestRenderer.ReactTestRenderer;
  ReactTestRenderer.act(() => {
    r = ReactTestRenderer.create(el);
  });
  mounted.push(r);
  return r;
}
const byLabel = (r: ReactTestRenderer.ReactTestRenderer, label: string) =>
  // The Pressable: it has onPress and a role; the wrapping control's element has no role prop.
  r.root.find(n => n.props.accessibilityLabel === label && typeof n.props.onPress === 'function' && n.props.accessibilityRole !== undefined);

test('a button is a button, labelled by its text unless told otherwise, and inert when disabled', () => {
  const onPress = jest.fn();
  const r = render(
    <RN.View>
      <Button label="Retry" onPress={onPress} />
      <Button label="Delete" accessibilityLabel="delete thing" appearance="outlined" destructive disabled onPress={onPress} />
    </RN.View>,
  );
  expect(byLabel(r, 'Retry').props.accessibilityRole).toBe('button');
  const del = byLabel(r, 'delete thing');
  expect(del.props.disabled).toBe(true);
  expect(del.props.accessibilityState).toEqual({ disabled: true });
});

test('an icon button asks for the text form of its glyph', () => {
  const r = render(<IconButton glyph="◀" accessibilityLabel="previous sentence" onPress={() => {}} />);
  expect(r.root.findByType(RN.Text).props.children).toBe('◀︎');
});

test('the stepper disables the end it has reached', () => {
  const onStep = jest.fn();
  const r = render(
    <Stepper value={4} min={0.5} max={4} format={v => `${v}x`} onStep={onStep} decreaseLabel="slower" increaseLabel="faster" />,
  );
  expect(byLabel(r, 'faster').props.disabled).toBe(true);
  expect(byLabel(r, 'slower').props.disabled).toBe(false);
  ReactTestRenderer.act(() => byLabel(r, 'slower').props.onPress());
  expect(onStep).toHaveBeenCalledWith(-1);
});

// REA-58: the Stepper is the Reader's rate control and Settings' "Default rate"; its value
// grows with the font scale so "4.0x" never pushes the buttons, and glyphs stop growing at 1.5x
// so they fit the 48 dp button (at 2.0 the glyph filled it, 179 px of 179, in REA-58's reproduction).
describe('at the largest font scale (REA-58)', () => {
  let dims: jest.SpyInstance;
  beforeEach(() => {
    dims = jest.spyOn(RN, 'useWindowDimensions').mockReturnValue({ width: 360, height: 800, scale: 3, fontScale: 2 });
  });
  afterEach(() => dims.mockRestore());
  const flat = (st: unknown) => RN.StyleSheet.flatten(st as never) ?? {};

  test("the Stepper's value is 44 dp per unit of font scale wide, its buttons stay 48 dp", () => {
    const r = render(<Stepper value={2} min={0.5} max={4} format={v => `${v.toFixed(1)}x`} onStep={() => {}} decreaseLabel="slower" increaseLabel="faster" />);
    const value = r.root.findAll(n => n.type === RN.Text).find(n => flat(n.props.style).fontVariant !== undefined)!;
    expect(flat(value.props.style).minWidth).toBe(88);
    expect(flat(byLabel(r, 'faster').props.style)).toMatchObject({ width: 48, height: 48 });
  });

  test('an IconButton glyph scales at most 1.5x', () => {
    const r = render(<IconButton glyph="+" accessibilityLabel="faster" onPress={() => {}} />);
    expect(byLabel(r, 'faster').findAll(n => n.type === RN.Text)[0].props.maxFontSizeMultiplier).toBe(1.5);
  });
});

test('the toggle is a switch whose label stays put and whose state is checked', () => {
  const onValueChange = jest.fn();
  const r = render(<Toggle label="Obsidian bridge" value onValueChange={onValueChange} />);
  const t = byLabel(r, 'Obsidian bridge');
  expect(t.props.accessibilityRole).toBe('switch');
  expect(t.props.accessibilityState).toMatchObject({ checked: true });
  ReactTestRenderer.act(() => t.props.onPress());
  expect(onValueChange).toHaveBeenCalledWith(false);
});

describe('Select', () => {
  const options = [
    { value: 'a-local', label: 'a-local', detail: 'en-US' },
    { value: 'b-local', label: 'b-local', detail: 'en-GB' },
  ];

  test('shows the current option, opens a sheet of radios and closes on a pick', () => {
    const onChange = jest.fn();
    const r = render(<Select name="voice" title="Voice" options={options} value="a-local" onChange={onChange} />);
    const field = byLabel(r, 'voice');
    expect(field.props.accessibilityRole).toBe('combobox');
    expect(field.props.accessibilityValue).toEqual({ text: 'a-local' });
    expect(r.root.findByType(RN.Modal).props.visible).toBe(false);

    ReactTestRenderer.act(() => field.props.onPress());
    expect(r.root.findByType(RN.Modal).props.visible).toBe(true);
    const a = byLabel(r, 'voice a-local');
    expect(a.props.accessibilityRole).toBe('radio');
    expect(a.props.accessibilityState).toEqual({ checked: true });
    expect(byLabel(r, 'voice b-local').props.accessibilityState).toEqual({ checked: false });

    ReactTestRenderer.act(() => byLabel(r, 'voice b-local').props.onPress());
    expect(onChange).toHaveBeenCalledWith('b-local');
    expect(r.root.findByType(RN.Modal).props.visible).toBe(false);
  });

  test('picking the current option, Back, or the scrim closes it without a change', () => {
    const onChange = jest.fn();
    const r = render(<Select name="voice" title="Voice" options={options} value="a-local" onChange={onChange} />);
    const modal = () => r.root.findByType(RN.Modal);
    ReactTestRenderer.act(() => byLabel(r, 'voice').props.onPress());
    ReactTestRenderer.act(() => byLabel(r, 'voice a-local').props.onPress());
    expect(modal().props.visible).toBe(false);
    ReactTestRenderer.act(() => byLabel(r, 'voice').props.onPress());
    ReactTestRenderer.act(() => modal().props.onRequestClose());
    expect(modal().props.visible).toBe(false);
    ReactTestRenderer.act(() => byLabel(r, 'voice').props.onPress());
    ReactTestRenderer.act(() => byLabel(r, 'close voice').props.onPress());
    expect(modal().props.visible).toBe(false);
    expect(onChange).not.toHaveBeenCalled();
  });

  // REA-32: an engine Android will not bind stays listed, with its reason, but cannot be picked.
  test('a disabled option is listed, marked disabled and cannot be picked', () => {
    const onChange = jest.fn();
    const opts = [...options, { value: 'c', label: 'Marmalade', detail: 'Needs Android 14 or later', disabled: true }];
    const r = render(<Select name="engine" title="Engine" options={opts} value="a-local" onChange={onChange} />);
    ReactTestRenderer.act(() => byLabel(r, 'engine').props.onPress());
    const c = byLabel(r, 'engine c');
    expect(c.props.accessibilityState).toEqual({ checked: false, disabled: true });
    expect(c.props.disabled).toBe(true);
  });

  test('with no current value the field shows the placeholder', () => {
    const r = render(<Select name="voice" title="Voice" options={options} value={null} placeholder="System default" onChange={() => {}} />);
    expect(byLabel(r, 'voice').props.accessibilityValue).toEqual({ text: 'System default' });
  });
});

describe('the sheet scrolls to the checked option without leaving a gap (REA-21)', () => {
  test('a list shorter than the sheet does not scroll', () => {
    // Two 64 dp rows in a 128 dp list: "Engine default" stays above the chosen voice.
    expect(selectOffset(1, 2, 128)).toBe(0);
  });
  test('a long list centres the checked row', () => {
    expect(selectOffset(10, 20, 320)).toBe(10 * 64 - (320 - 64) / 2);
  });
  test('never past the last row, nor for nothing checked', () => {
    expect(selectOffset(19, 20, 320)).toBe(20 * 64 - 320);
    expect(selectOffset(-1, 20, 320)).toBe(0);
  });
});
