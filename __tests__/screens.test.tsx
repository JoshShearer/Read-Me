import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type { NativeItem } from '../src/native/NativeReadMeSpeech';

const mockState = {
  items: [] as NativeItem[],
  detail: null as unknown,
  engine: {
    status: 'ready',
    voices: [] as unknown[],
    selected: null as string | null,
    engine: null as string | null,
    engineLabel: null as string | null,
    choosable: false,
    engines: [] as unknown[],
  },
  bridge: { enabled: false, state: 'off', port: 8787, token: null as string | null, error: null as string | null },
};
const base: NativeItem = {
  id: 1, kind: 'link', url: 'https://example.com/a', title: 'An article', site: null, byline: null,
  createdAt: 1, state: 'ready', failReason: null, openedAt: null, archivedAt: null, words: 0, progress: 0,
};

jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    listItems: jest.fn(async () => mockState.items),
    getItem: jest.fn(async () => mockState.detail),
    getBody: jest.fn(async () => null),
    completeExtraction: jest.fn(async () => true),
    getPlayback: jest.fn(async () => ({
      itemId: null, playing: false, paragraphIndex: -1, start: 0, end: 0, rate: 2, engine: 'unknown',
    })),
    getEngine: jest.fn(async () => mockState.engine),
    getRate: jest.fn(async () => 2),
    getPosition: jest.fn(async () => null),
    setCuts: jest.fn(async () => undefined),
    getBridge: jest.fn(async () => mockState.bridge),
    setBridgeEnabled: jest.fn(async (on: boolean) => {
      mockState.bridge = { ...mockState.bridge, enabled: on, state: on ? 'on' : 'off', token: on ? '0123456789abcdef0123456789abcdef' : null };
      return mockState.bridge;
    }),
    setBridgePort: jest.fn(async () => mockState.bridge),
    regenerateBridgeToken: jest.fn(async () => mockState.bridge),
    copyBridgeToken: jest.fn(async () => undefined),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

// Select's bottom sheet pads for the navigation bar; App provides the insets on the phone.
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

import Native from '../src/native/NativeReadMeSpeech';
import { ListScreen } from '../src/ui/ListScreen';
import { TrimScreen } from '../src/ui/TrimScreen';
import { ReaderScreen } from '../src/ui/ReaderScreen';
import { SettingsScreen } from '../src/ui/SettingsScreen';
import * as bridgeLib from '../src/library/bridge';

type Node = ReactTestRenderer.ReactTestRendererNode | ReactTestRenderer.ReactTestRendererNode[] | null;

// Visible strings plus accessibility labels (quoted), in render order. JSON.stringify of the
// tree is circular once a FlatList is in it.
function strings(node: Node, out: string[] = []): string[] {
  if (node === null) return out;
  if (typeof node === 'string') out.push(node);
  else if (Array.isArray(node)) node.forEach(n => strings(n, out));
  else {
    const label = node.props.accessibilityLabel;
    if (typeof label === 'string') out.push(`"${label}"`);
    strings(node.children, out);
  }
  return out;
}

// Unmounted after each test: a FlatList left mounted schedules updates after its test ends,
// which Jest reports as a failure of whichever test is running (seen in a full-suite run).
const mounted: ReactTestRenderer.ReactTestRenderer[] = [];
afterEach(async () => {
  await ReactTestRenderer.act(async () => {
    while (mounted.length) mounted.pop()!.unmount();
  });
});

async function render(el: React.ReactElement) {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(el);
  });
  mounted.push(r);
  return strings(r.toJSON()).join('\n');
}

beforeEach(() => {
  mockState.items = [];
  mockState.detail = null;
  mockState.engine = { status: 'ready', voices: [], selected: null, engine: null, engineLabel: null, choosable: false, engines: [] };
  mockState.bridge = { enabled: false, state: 'off', port: 8787, token: null, error: null };
});

test('a failed link shows its reason, Retry, Delete and the share-text guidance (R-M10)', async () => {
  mockState.items = [{ ...base, state: 'fetch-failed', failReason: 'offline', title: 'example.com' }];
  const out = await render(<ListScreen onOpen={() => {}} onSettings={() => {}} />);
  expect(out).toContain('fetch-failed: offline');
  expect(out).toContain('Retry');
  expect(out).toContain('Delete');
  expect(out).toContain('share the text to Read Me');
});

test('a ready text shows Shared text, its words and progress', async () => {
  mockState.items = [{ ...base, kind: 'text', url: null, words: 300, progress: 0.5 }];
  const out = await render(<ListScreen onOpen={() => {}} onSettings={() => {}} />);
  expect(out).toContain('Shared text · 300 words · 50% read');
});

test('an empty list says how to add something', async () => {
  const out = await render(<ListScreen onOpen={() => {}} onSettings={() => {}} />);
  expect(out).toContain('Share a link or text to Read Me.');
});

test('Trim shows kept and cut paragraphs', async () => {
  mockState.detail = {
    item: { ...base, kind: 'text' },
    paragraphs: [{ kind: 'p', text: 'First.' }, { kind: 'p', text: 'Second.' }],
    cuts: [1],
  };
  const out = await render(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
  expect(out).toContain('1 of 2 paragraphs kept');
  expect(out).toContain('First.');
  expect(out).toContain('cut');
});

test('quick Trim taps keep every cut (final review Important 2)', async () => {
  mockState.detail = {
    item: { ...base, kind: 'text' },
    paragraphs: [{ kind: 'p', text: 'First.' }, { kind: 'p', text: 'Second.' }, { kind: 'p', text: 'Third.' }],
    cuts: [],
  };
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
  });
  mounted.push(r);
  const tap = (label: string) => r.root.find(n => n.props.accessibilityLabel === label && typeof n.props.onPress === 'function').props.onPress();
  // Two taps before the store's change event brings back a fresh item.
  await ReactTestRenderer.act(async () => {
    tap('paragraph 1');
    tap('paragraph 2');
  });
  const calls = (Native.setCuts as jest.Mock).mock.calls;
  expect(calls[calls.length - 1]).toEqual([1, [0, 1]]);
});

test('Trim of a deleted item says so', async () => {
  const out = await render(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
  expect(out).toContain('This item was deleted.');
});

const text = (paragraphs: string[], cuts: number[] = []) => ({
  item: { ...base, kind: 'text', openedAt: 1 },
  paragraphs: paragraphs.map(t => ({ kind: 'p', text: t })),
  cuts,
});

test('Reader shows a deleted item as gone (Review Focus 2)', async () => {
  const out = await render(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  expect(out).toContain('This item was deleted.');
});

test('Reader with every paragraph cut offers Trim (Review Focus 3)', async () => {
  mockState.detail = text(['Only.'], [0]);
  const out = await render(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  expect(out).toContain('Everything in this item is cut.');
  expect(out).not.toContain('"play"');
});

test('Reader blocks when the engine has no offline voice (Review Focus 4)', async () => {
  mockState.detail = text(['One.']);
  mockState.engine = { status: 'no-voice', voices: [], selected: null, engine: null, engineLabel: null, choosable: false, engines: [] };
  const out = await render(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  expect(out).toContain('no offline text-to-speech voice');
  expect(out).toContain('Open text-to-speech settings');
  expect(out).not.toContain('"play"');
});

test('Reader shows kept paragraphs and the transport', async () => {
  mockState.detail = text(['Kept one.', 'Cut one.', 'Kept two.'], [1]);
  const out = await render(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  expect(out).toContain('Kept one.');
  expect(out).toContain('Kept two.');
  expect(out).not.toContain('Cut one.');
  expect(out).toContain('"play"');
  expect(out).toContain('2.0x');
});

test('Reader disables sentence controls until its item is the one the service holds', async () => {
  // /critique run A F2: next/previous/back paragraph act on whatever the service holds. They
  // stay in place (disabled) so Play/Pause never moves under the user's finger.
  mockState.detail = text(['One.']);
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  });
  mounted.push(r);
  const pressable = (label: string) =>
    r.root.find(n => n.props.accessibilityLabel === label && typeof n.props.onPress === 'function');
  for (const label of ['next sentence', 'previous sentence', 'back paragraph']) {
    expect(pressable(label).props.disabled).toBe(true);
  }
  expect(pressable('play').props.disabled).toBeFalsy();
});

test('Settings lists offline voices, the default rate and storage', async () => {
  mockState.engine = {
    status: 'ready',
    voices: [{ name: 'en-us-x-a-local', language: 'en', quality: 400, label: 'English (United States)', detail: 'High quality' }],
    selected: 'en-us-x-a-local',
    engine: 'com.google.android.tts',
    engineLabel: 'Speech Services by Google',
    choosable: false,
    engines: [],
  };
  mockState.items = [base, { ...base, id: 2, archivedAt: 3 }];
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  // REA-32: the voice reads as its language, not its engine's id.
  expect(out).toContain('English (United States)');
  expect(out).toContain('High quality');
  // REA-28: the voice is a Select, not one row per voice.
  expect(out).toContain('"voice"');
  expect(out).toContain('2.0x');
  expect(out).toContain('2 items, 1 archived');
  expect(out).toContain('Licenses');
});

// REA-28: the bridge is a switch whose label stays "Use the bridge"; its state is checked.
const bridgeSwitch = (r: ReactTestRenderer.ReactTestRenderer) =>
  r.root.find(n => n.props.accessibilityRole === 'switch' && n.props.accessibilityLabel === 'Use the bridge');

async function settings() {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<SettingsScreen onLicenses={() => {}} />);
  });
  mounted.push(r);
  return r;
}

test('Settings has its voice picker and bridge switch from the first frame, disabled while loading', async () => {
  // REA-28: adding them when the data arrived, mid-mount, lost Fabric mutations on the phone.
  const never = new Promise<never>(() => {});
  (Native.getEngine as jest.Mock).mockReturnValueOnce(never);
  (Native.getBridge as jest.Mock).mockReturnValueOnce(never);
  const r = await settings();
  const field = r.root.find(n => n.props.accessibilityRole === 'combobox' && typeof n.props.onPress === 'function');
  expect(field.props.disabled).toBe(true);
  expect(field.props.accessibilityValue).toBeUndefined();
  expect(bridgeSwitch(r).props.disabled).toBe(true);
  expect(strings(r.toJSON()).join('\n')).toContain('Checking the text-to-speech engine...');
});

test('Settings shows the bridge off, with no token (R-M12)', async () => {
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('Obsidian bridge');
  expect(out).toContain('Off');
  expect(out).not.toContain('pairing token');
  expect(bridgeSwitch(await settings()).props.accessibilityState).toMatchObject({ checked: false });
});

test('Settings shows the token with Copy and New token while the bridge is on', async () => {
  mockState.bridge = { enabled: true, state: 'on', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('On at 127.0.0.1:8787');
  expect(out).toContain('0123456789abcdef0123456789abcdef');
  expect(out).toContain('"copy token"');
  expect(out).toContain('"new token"');
  expect(bridgeSwitch(await settings()).props.accessibilityState).toMatchObject({ checked: true });
});

test('turning the bridge on goes through the module', async () => {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<SettingsScreen onLicenses={() => {}} />);
  });
  mounted.push(r);
  await ReactTestRenderer.act(async () => {
    bridgeSwitch(r).props.onPress();
  });
  expect(Native.setBridgeEnabled).toHaveBeenCalledWith(true);
  expect(strings(r.toJSON()).join('\n')).toContain('0123456789abcdef0123456789abcdef');
});

test('the bridge does not turn on while notifications are refused (R-M12)', async () => {
  const spy = jest.spyOn(bridgeLib, 'ensureNotifications').mockResolvedValue(false);
  (Native.setBridgeEnabled as jest.Mock).mockClear();
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<SettingsScreen onLicenses={() => {}} />);
  });
  mounted.push(r);
  await ReactTestRenderer.act(async () => {
    bridgeSwitch(r).props.onPress();
  });
  expect(spy).toHaveBeenCalled();
  expect(Native.setBridgeEnabled).not.toHaveBeenCalled();
  expect(strings(r.toJSON()).join('\n')).toContain('Read Me needs to show a notification while the bridge is on');
  spy.mockRestore();
});

test('an enabled bridge without notification permission is flagged when Settings opens', async () => {
  const spy = jest.spyOn(bridgeLib, 'notificationsAllowed').mockResolvedValue(false);
  mockState.bridge = { enabled: true, state: 'on', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('Read Me needs to show a notification while the bridge is on');
  spy.mockRestore();
});

test('notifications off with the bridge off shows no bridge warning', async () => {
  const spy = jest.spyOn(bridgeLib, 'notificationsAllowed').mockResolvedValue(false);
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).not.toContain('Read Me needs to show a notification');
  spy.mockRestore();
});

// REA-24: the Material 3 palette on the rendered screens, in dark mode (light is the default
// the tests above already render in).
describe('theme on the screens (REA-24)', () => {
  const RN = require('react-native') as typeof import('react-native');
  const { blend, FAINT, palette } = require('../src/ui/theme') as typeof import('../src/ui/theme');
  const p = palette('dark');
  let scheme: jest.SpyInstance;
  beforeEach(() => {
    scheme = jest.spyOn(RN, 'useColorScheme').mockReturnValue('dark');
  });
  afterEach(() => scheme.mockRestore());

  async function create(el: React.ReactElement) {
    let r!: ReactTestRenderer.ReactTestRenderer;
    await ReactTestRenderer.act(async () => {
      r = ReactTestRenderer.create(el);
    });
    mounted.push(r);
    return r;
  }
  const flat = (s: unknown) => RN.StyleSheet.flatten(s as never) ?? {};
  // The host Text inside the Pressable with this label.
  const labelStyle = (r: ReactTestRenderer.ReactTestRenderer, label: string) =>
    flat(
      r.root
        .find(n => n.props.accessibilityLabel === label && typeof n.props.onPress === 'function')
        .findAll(n => n.type === RN.Text)[0].props.style,
    );
  const texts = (r: ReactTestRenderer.ReactTestRenderer) => r.root.findAll(n => n.type === RN.Text);

  test('every Text on List, Trim, Reader and Settings takes a palette colour', async () => {
    const roles = [
      p.onSurface, p.onSurfaceVariant, p.primary, p.error, p.onPrimaryContainer, blend(p.onSurface, p.surface, FAINT),
      // REA-28: labels on filled and tonal buttons, and the selected option in a Select.
      p.onPrimary, p.onSecondaryContainer,
    ];
    mockState.items = [{ ...base, state: 'fetch-failed', failReason: 'offline', title: 'example.com' }];
    mockState.detail = text(['One.', 'Two.'], [1]);
    mockState.bridge = { enabled: true, state: 'on', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
    for (const el of [
      <ListScreen onOpen={() => {}} onSettings={() => {}} />,
      <TrimScreen id={1} onDone={() => {}} onGone={() => {}} />,
      <ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />,
      <SettingsScreen onLicenses={() => {}} />,
    ]) {
      const r = await create(el);
      for (const t of texts(r)) expect(roles).toContain(flat(t.props.style).color);
    }
  });

  test('buttons are primary with no underline; disabled ones are onSurface at FAINT', async () => {
    mockState.detail = text(['One.']);
    const r = await create(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    // REA-28: Play is a filled button, onPrimary on primary.
    expect(labelStyle(r, 'play')).toMatchObject({ color: p.onPrimary });
    expect(labelStyle(r, 'play').textDecorationLine).toBeUndefined();
    expect(labelStyle(r, 'trim')).toMatchObject({ color: p.primary });
    expect(labelStyle(r, 'next sentence')).toMatchObject({ color: blend(p.onSurface, p.surface, FAINT) });
    expect(labelStyle(r, 'next sentence').opacity).toBeUndefined();
  });

  test('the Reader highlights the current sentence in primaryContainer', async () => {
    mockState.detail = text(['One two. Three four.']);
    (Native.getPlayback as jest.Mock).mockResolvedValueOnce({
      itemId: 1, playing: true, paragraphIndex: 0, start: 0, end: 8, rate: 2, engine: 'ready',
    });
    const r = await create(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    const hl = texts(r).map(t => flat(t.props.style)).find(s => s.backgroundColor !== undefined);
    expect(hl).toMatchObject({ backgroundColor: p.primaryContainer, color: p.onPrimaryContainer });
  });

  test('a cut Trim paragraph is onSurface at FAINT and struck through', async () => {
    mockState.detail = text(['Kept.', 'Gone.'], [1]);
    const r = await create(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
    const cut = flat(r.root.find(n => n.props.accessibilityLabel === 'paragraph 2 cut' && typeof n.props.onPress === 'function')
      .findAll(n => n.type === RN.Text)[0].props.style);
    expect(cut).toMatchObject({ color: blend(p.onSurface, p.surface, FAINT), textDecorationLine: 'line-through' });
    expect(cut.opacity).toBeUndefined();
  });

  test('cards and the bridge row sit on surfaceContainerLow with an outlineVariant border', async () => {
    mockState.detail = text(['One.']);
    mockState.engine = { status: 'no-voice', voices: [], selected: null, engine: null, engineLabel: null, choosable: false, engines: [] };
    const r = await create(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    const card = r.root.findAll(n => n.type === RN.View && flat(n.props.style).borderWidth === 1)[0];
    expect(flat(card.props.style)).toMatchObject({ backgroundColor: p.surfaceContainerLow, borderColor: p.outlineVariant });

    mockState.bridge = { enabled: true, state: 'on', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
    const s = await create(<SettingsScreen onLicenses={() => {}} />);
    // The innermost screen View (collapsable={false}, not the Pressable's own) that holds the
    // bridge's switch.
    const rows = s.root.findAll(
      n => n.type === RN.View && n.props.collapsable === false && n.props.accessibilityLabel === undefined && n.findAll(m => m.props.accessibilityRole === 'switch').length > 0,
    );
    expect(flat(rows[rows.length - 1].props.style)).toMatchObject({ backgroundColor: p.surfaceContainerLow });
  });

  test('the port field follows the palette', async () => {
    const s = await create(<SettingsScreen onLicenses={() => {}} />);
    await ReactTestRenderer.act(async () => {
      bridgeSwitch(s).props.onPress();
    });
    const input = s.root.find(n => n.type === RN.TextInput);
    expect(flat(input.props.style)).toMatchObject({ color: p.onSurface });
    expect(input.props.placeholderTextColor).toBe(p.onSurfaceVariant);
    expect(input.props.selectionColor).toBe(p.primary);
  });
});
