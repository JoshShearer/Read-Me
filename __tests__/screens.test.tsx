import React from 'react';
import { Alert, AppState, DeviceEventEmitter, Linking, Modal } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import type { NativeEngine, NativeItem } from '../src/native/NativeReadMeSpeech';

const mockState = {
  items: [] as NativeItem[],
  detail: null as unknown,
  engine: {
    status: 'ready',
    voices: [],
    selected: null,
    engine: null,
    engineLabel: null,
    choosable: false,
    engines: [],
  } as NativeEngine,
  bridge: { enabled: false, state: 'off', port: 8787, token: null as string | null, error: null as string | null },
  continuous: false,
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
    setRate: jest.fn(async (rate: number) => rate),
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
    readerShows: jest.fn(),
    play: jest.fn(async () => true),
    pause: jest.fn(async () => true),
    resume: jest.fn(async () => true),
    getContinuousPlay: jest.fn(async () => mockState.continuous),
    setVoice: jest.fn(async () => undefined),
    setContinuousPlay: jest.fn(async (on: boolean) => {
      mockState.continuous = on;
      return on;
    }),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

// Select's bottom sheet pads for the navigation bar; App provides the insets on the phone.
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

import '../jest/loadReactNative';
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

// Jest's react-native AppState returns no subscription. The Reader listens for activity
// (REA-56) and, while it blocks, to probe again (REA-21): every live handler hears a change.
let appHandlers: ((s: string) => void)[] = [];
const appChange = (s: string) => ReactTestRenderer.act(async () => appHandlers.slice().forEach(h => h(s)));
// The preset's AppState.currentState is a jest.fn, not a state; the Reader reads it on mount.
let currentState: PropertyDescriptor | undefined;
const setCurrentState = (v: string) =>
  Object.defineProperty(AppState, 'currentState', { value: v, configurable: true, writable: true });
beforeEach(() => {
  appHandlers = [];
  currentState = Object.getOwnPropertyDescriptor(AppState, 'currentState');
  setCurrentState('active');
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_e, h) => {
    const f = h as (s: string) => void;
    appHandlers.push(f);
    return { remove: () => { appHandlers = appHandlers.filter(x => x !== f); } } as ReturnType<typeof AppState.addEventListener>;
  });
});
afterEach(() => {
  jest.restoreAllMocks();
  if (currentState) Object.defineProperty(AppState, 'currentState', currentState);
});

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
  expect(out).toContain('In any app, tap Share and pick Read Me Offline to add a link or text.');
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
  const tap = (id: string) => r.root.find(n => n.props.testID === id && typeof n.props.onPress === 'function').props.onPress();
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

// R-M10, REA-50: only a missing item is "deleted"; any other failure has its own state.
const failure = () => new Error('library operation failed: SQLiteException');

async function mount(el: React.ReactElement) {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(el);
  });
  mounted.push(r);
  return r;
}
const shown = (r: ReactTestRenderer.ReactTestRenderer) => strings(r.toJSON()).join('\n');
const press = (r: ReactTestRenderer.ReactTestRenderer, label: string) =>
  ReactTestRenderer.act(async () => {
    r.root.find(n => n.props.accessibilityLabel === label && typeof n.props.onPress === 'function').props.onPress();
  });

for (const [name, el] of [
  ['Reader', () => <ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />],
  ['Trim', () => <TrimScreen id={1} onDone={() => {}} onGone={() => {}} />],
] as const) {
  test(`${name}: a getItem failure is not "deleted" and offers Retry and Back`, async () => {
    (Native.getItem as jest.Mock).mockRejectedValueOnce(failure());
    const r = await mount(el());
    expect(shown(r)).toContain('Could not open this item.');
    expect(shown(r)).not.toContain('This item was deleted.');
    expect(shown(r)).toContain('Back to the list');
    mockState.detail = {
      item: { ...base, kind: 'text', openedAt: 1 },
      paragraphs: [{ kind: 'p', text: 'Recovered.' }],
      cuts: [],
    };
    await press(r, 'Retry');
    expect(shown(r)).toContain('Recovered.');
    expect(shown(r)).not.toContain('Could not open this item.');
  });

  test(`${name}: keeps its text when a reload fails`, async () => {
    mockState.detail = {
      item: { ...base, kind: 'text', openedAt: 1 },
      paragraphs: [{ kind: 'p', text: 'Loaded.' }],
      cuts: [],
    };
    const r = await mount(el());
    (Native.getItem as jest.Mock).mockRejectedValueOnce(failure());
    await ReactTestRenderer.act(async () => {
      DeviceEventEmitter.emit('ReadMeItemsChanged');
    });
    expect(shown(r)).toContain('Loaded.');
    expect(shown(r)).not.toContain('Could not open this item.');
  });

  test(`${name}: deleted stays deleted when a later reload fails`, async () => {
    const r = await mount(el());
    expect(shown(r)).toContain('This item was deleted.');
    (Native.getItem as jest.Mock).mockRejectedValueOnce(failure());
    await ReactTestRenderer.act(async () => {
      DeviceEventEmitter.emit('ReadMeItemsChanged');
    });
    expect(shown(r)).toContain('This item was deleted.');
    expect(shown(r)).not.toContain('Could not open this item.');
  });
}

test('Trim paragraphs are read by their own text, with a hint (REA-50 TalkBack)', async () => {
  mockState.detail = {
    item: { ...base, kind: 'text' },
    paragraphs: [{ kind: 'p', text: 'First.' }, { kind: 'p', text: 'Second.' }],
    cuts: [1],
  };
  const r = await mount(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
  const node = r.root.find(n => n.props.testID === 'paragraph 2 cut' && typeof n.props.onPress === 'function');
  expect(node.props.accessibilityLabel).toBeUndefined();
  const inside = node.findAll(n => typeof n.props.children === 'string').map(n => n.props.children);
  expect(inside).toEqual(expect.arrayContaining(['Second.', 'cut']));
  expect(node.props.accessibilityHint).toMatch(/restore/);
  const kept = r.root.find(n => n.props.testID === 'paragraph 1' && typeof n.props.onPress === 'function');
  expect(kept.props.accessibilityHint).toMatch(/cut/);
  expect(r.root.findAll(n => typeof n.props.accessibilityLabel === 'string' && /^paragraph /.test(n.props.accessibilityLabel)).length).toBe(0);
});

test("Reader's current paragraph is read by its text and marked selected (REA-50 TalkBack)", async () => {
  mockState.detail = text(['One two. Three four.']);
  (Native.getPlayback as jest.Mock).mockResolvedValueOnce({
    itemId: 1, playing: true, paragraphIndex: 0, start: 0, end: 8, rate: 2, engine: 'ready',
  });
  const r = await mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  expect(r.root.findAll(n => n.props.accessibilityLabel === 'current paragraph').length).toBe(0);
  const current = r.root.findAll(n => n.props.testID === 'current paragraph');
  expect(current.length).toBeGreaterThan(0);
  const selected = current[0].findAll(n => n.props.accessibilityState?.selected === true);
  expect(selected.length).toBeGreaterThan(0);
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

test('Reader checks the engine again when the app comes back while it blocks (REA-21)', async () => {
  // The card sends the user to Android's TTS settings; before, it stayed until the Reader was
  // reopened, even after an offline voice was installed.
  mockState.detail = text(['One.']);
  mockState.engine = { ...mockState.engine, status: 'no-voice' };
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  });
  mounted.push(r);
  expect(strings(r.toJSON()).join('\n')).toContain('no offline text-to-speech voice');
  mockState.engine = { ...mockState.engine, status: 'ready' };
  // While it blocks, two listen: the probe's and the Reader's own activity (REA-56).
  expect(appHandlers.length).toBe(2);
  await appChange('active');
  const out = strings(r.toJSON()).join('\n');
  expect(out).not.toContain('no offline text-to-speech voice');
  expect(out).toContain('"play"');
  // Once it reads, the probe's listener is gone: the check is only for the blocking card.
  // Only the Reader's activity listener is left, and a return checks the engine no more.
  expect(appHandlers.length).toBe(1);
  const probes = (Native.getEngine as jest.Mock).mock.calls.length;
  await appChange('active');
  expect((Native.getEngine as jest.Mock).mock.calls.length).toBe(probes);
});

test("Reader drops an engine answer older than the newest check (REA-21)", async () => {
  const getEngine = Native.getEngine as jest.Mock;
  mockState.detail = text(['One.']);
  mockState.engine = { ...mockState.engine, status: 'no-voice' };
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  });
  mounted.push(r);
  // A slow check (a busy engine), then a quick one after another switch.
  let slow!: (e: NativeEngine) => void;
  getEngine.mockImplementationOnce(() => new Promise<NativeEngine>(res => { slow = res; }));
  await appChange('active');
  getEngine.mockImplementationOnce(async () => ({ ...mockState.engine, status: 'ready' }));
  await appChange('active');
  expect(strings(r.toJSON()).join('\n')).toContain('"play"');
  await ReactTestRenderer.act(async () => slow({ ...mockState.engine, status: 'no-voice' }));
  const out = strings(r.toJSON()).join('\n');
  expect(out).not.toContain('no offline text-to-speech voice');
  expect(out).toContain('"play"');
});

describe('the Reader writes which item it shows, ids and state only (REA-56)', () => {
  const shows = Native.readerShows as jest.Mock;
  const snap = (itemId: number, playing: boolean, paragraphIndex = 0, start = 0, end = 9) =>
    ({ itemId, playing, paragraphIndex, start, end, rate: 2, engine: 'ready' });
  const event = (n: object) => ReactTestRenderer.act(async () => { DeviceEventEmitter.emit('ReadMePlayback', n); });
  const reader = () => mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
  beforeEach(() => {
    shows.mockClear();
    (Native.play as jest.Mock).mockClear();
    (Native.pause as jest.Mock).mockClear();
    (Native.resume as jest.Mock).mockClear();
  });

  test('once when its item is shown playing, never per sentence, once more on a pause', async () => {
    mockState.detail = text(['One two. Three four.', 'Five six.']);
    await reader();
    expect(shows).not.toHaveBeenCalled();
    await event(snap(1, true));
    expect(shows.mock.calls).toEqual([[1, true]]);
    await event(snap(1, true, 0, 9, 20));
    await event(snap(1, true, 1, 0, 9));
    expect(shows).toHaveBeenCalledTimes(1);
    await event(snap(1, false, 1, 0, 9));
    expect(shows.mock.calls).toEqual([[1, true], [1, false]]);
    // AGENTS.md 11: it only writes a line; playback is never driven by it.
    expect(Native.play).not.toHaveBeenCalled();
    expect(Native.pause).not.toHaveBeenCalled();
    expect(Native.resume).not.toHaveBeenCalled();
  });

  test('not while another item plays', async () => {
    mockState.detail = text(['One.']);
    await reader();
    await event(snap(2, true));
    expect(shows).not.toHaveBeenCalled();
  });

  test('not while the engine blocks', async () => {
    mockState.detail = text(['One.']);
    mockState.engine = { ...mockState.engine, status: 'no-voice' };
    await reader();
    await event(snap(1, true));
    expect(shows).not.toHaveBeenCalled();
  });

  test('not when the position is in a cut paragraph (nothing highlighted)', async () => {
    mockState.detail = text(['Cut.', 'Kept.'], [0]);
    await reader();
    await event(snap(1, true, 0, 0, 4));
    expect(shows).not.toHaveBeenCalled();
  });

  test('a Reader that got its item in the background writes it when the app is active again', async () => {
    setCurrentState('background');
    mockState.detail = text(['One.']);
    await reader();
    await event(snap(1, true));
    expect(shows).not.toHaveBeenCalled();
    await appChange('background');
    expect(shows).not.toHaveBeenCalled();
    await appChange('active');
    expect(shows.mock.calls).toEqual([[1, true]]);
  });
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
    preferred: 'en-us-x-a-local',
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

test("Settings offers the engine's default voice and choosing it clears the stored voice (REA-21)", async () => {
  const voice = { name: 'en-us-x-a-local', language: 'en', quality: 400, label: 'English (United States)', detail: '' };
  mockState.engine = { ...mockState.engine, voices: [voice], selected: voice.name, preferred: null };
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<SettingsScreen onLicenses={() => {}} />);
  });
  mounted.push(r);
  const byLabel = (label: string) =>
    r.root.find(n => n.props.accessibilityLabel === label && typeof n.props.onPress === 'function');
  // Nothing stored: the engine's default is the checked row, first in the sheet.
  await ReactTestRenderer.act(async () => byLabel('voice').props.onPress());
  expect(byLabel('voice engine-default').props.accessibilityState).toEqual({ checked: true });
  expect(byLabel(`voice ${voice.name}`).props.accessibilityState).toEqual({ checked: false });
  mockState.engine = { ...mockState.engine, preferred: voice.name };
  await ReactTestRenderer.act(async () => byLabel(`voice ${voice.name}`).props.onPress());
  expect(Native.setVoice).toHaveBeenLastCalledWith(voice.name);
  // Back to the default. The sheet opens scrolled to the checked voice, so the default row is
  // above it; the choice goes through the Select's onChange either way.
  const select = r.root.find(n => n.props.name === 'voice' && typeof n.props.onChange === 'function');
  expect(select.props.value).toBe(voice.name);
  mockState.engine = { ...mockState.engine, preferred: null };
  await ReactTestRenderer.act(async () => select.props.onChange('engine-default'));
  expect(Native.setVoice).toHaveBeenLastCalledWith(null);
  expect(r.root.find(n => n.props.name === 'voice' && typeof n.props.onChange === 'function').props.value).toBe('engine-default');
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

// REA-60, R-M12: the native guard reports 'blocked' while Read Me's notifications cannot show.
test('a blocked bridge shows as off, with the reason, the way back and no token', async () => {
  mockState.bridge = { enabled: true, state: 'blocked', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('Off: notifications are off for Read Me.');
  expect(out).toContain('Read Me needs to show a notification while the bridge is on');
  expect(out).toContain('Allow them in Android settings and the bridge comes back on.');
  expect(out).toContain('"open app settings"');
  expect(out).not.toContain('0123456789abcdef0123456789abcdef');
  expect(bridgeSwitch(await settings()).props.accessibilityState).toMatchObject({ checked: false });
});

test('the bridge section says it can be turned off from its notification or here', async () => {
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('While it is on, a notification says so. You can turn the bridge off from that notification or here.');
});

test('returning to Settings from Android settings reads the bridge and the permission again', async () => {
  const allowed = jest.spyOn(bridgeLib, 'notificationsAllowed').mockResolvedValue(false);
  mockState.bridge = { enabled: true, state: 'blocked', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
  const r = await settings();
  const calls = (Native.getBridge as jest.Mock).mock.calls.length;
  allowed.mockResolvedValue(true);
  mockState.bridge = { ...mockState.bridge, state: 'on' };
  await appChange('active');
  expect((Native.getBridge as jest.Mock).mock.calls.length).toBeGreaterThan(calls);
  const out = strings(r.toJSON()).join('\n');
  expect(out).toContain('On at 127.0.0.1:8787');
  expect(out).not.toContain('Read Me needs to show a notification');
  expect(bridgeSwitch(r).props.accessibilityState).toMatchObject({ checked: true });
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
    const cut = flat(r.root.find(n => n.props.testID === 'paragraph 2 cut' && typeof n.props.onPress === 'function')
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

// --- R-S05: the Continuous play switch ---

const continuousSwitch = (r: ReactTestRenderer.ReactTestRenderer) =>
  r.root.find(n => n.props.accessibilityRole === 'switch' && n.props.accessibilityLabel === 'Continuous play');

test('Settings has a Continuous play switch, off by default, that goes through the module', async () => {
  mockState.continuous = false;
  const r = await settings();
  expect(continuousSwitch(r).props.accessibilityState).toMatchObject({ checked: false, disabled: false });
  expect(strings(r.toJSON()).join('\n')).toContain('go on to the next unread item');
  await ReactTestRenderer.act(async () => {
    continuousSwitch(r).props.onPress();
  });
  expect(Native.setContinuousPlay).toHaveBeenCalledWith(true);
  expect(continuousSwitch(r).props.accessibilityState).toMatchObject({ checked: true });
  mockState.continuous = false;
});

test('the Continuous play switch is there and disabled before the setting loads (REA-28)', async () => {
  (Native.getContinuousPlay as jest.Mock).mockReturnValueOnce(new Promise<never>(() => {}));
  const r = await settings();
  expect(continuousSwitch(r).props.disabled).toBe(true);
});

// REA-61: first-time setup for the Obsidian plugin.
const PLUGIN_URL = 'https://github.com/JoshShearer/Note-Reader-Local';

test('the bridge section gives four ordered setup steps a fresh install can follow (REA-61)', async () => {
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('The plugin is a separate install');
  expect(out).toContain("1. In Obsidian, search Local TTS Reader in Community plugins, then install and enable it.");
  expect(out).toContain('2. Turn on "Use the bridge" below and allow the notification.');
  expect(out).toContain('3. Copy the token that appears below');
  expect(out).toContain('Pairing token');
  expect(out).toContain('Read Me Offline bridge');
  expect(out).toContain('Speech engine on Automatic');
  expect(out).toContain('4. In Obsidian, open a note and run "Read note aloud"');
  expect(out).toContain('Read this note aloud');
  // the switch the steps point at sits after them
  expect(out.lastIndexOf('Use the bridge')).toBeGreaterThan(out.indexOf('4. In Obsidian'));
});

// REA-70: the screen-off caveat sits after step 4, before the switch.
test('the bridge section says screen-off reading needs a newer plugin than 0.2.1 (REA-70)', async () => {
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  const caveat =
    "Reading with the screen off needs a Local TTS Reader version newer than 0.2.1. With 0.2.1, Android can stop Obsidian's audio a few seconds after Obsidian leaves the screen, so keep Obsidian open.";
  expect(out).toContain(caveat);
  expect(out.indexOf(caveat)).toBeGreaterThan(out.indexOf('4. In Obsidian'));
  expect(out.indexOf(caveat)).toBeLessThan(out.lastIndexOf('Use the bridge'));
});

test('the plugin link is a plain text link, not a tonal fill, and does not stretch (REA-61)', async () => {
  const r = await settings();
  const link = r.root.find(n => n.props.accessibilityLabel === 'open plugin page' && n.props.accessibilityRole !== undefined);
  const flat = ([] as object[]).concat(...[link.props.style].flat(5).map(x => x ?? [])) as Array<Record<string, unknown>>;
  const merged = Object.assign({}, ...flat);
  // A tonal fill (secondaryContainer #e8ddf8) antialiases into the Reader-highlight match of
  // scripts/theme-contrast.py (primaryContainer #ecdcff +-6).
  expect(merged.backgroundColor).toBeUndefined();
  expect(merged.alignSelf).toBe('flex-start');
});

test('the plugin link hands off to the browser with the exact URL and is a labelled link (REA-61)', async () => {
  const open = jest.spyOn(Linking, 'openURL').mockImplementation(async () => undefined as never);
  const r = await settings();
  const link = r.root.find(n => n.props.accessibilityLabel === 'open plugin page' && n.props.accessibilityRole !== undefined);
  expect(link.props.accessibilityRole).toBe('link');
  await ReactTestRenderer.act(async () => {
    link.props.onPress();
  });
  expect(open).toHaveBeenCalledWith(PLUGIN_URL);
  open.mockRestore();
});

// REA-58: on a 360 dp phone (a Galaxy S22) the one-row dock put "+" off the screen. A narrow
// window, or a large font, gets two rows: the transport, then the rate Stepper. Jest runs no
// layout, so this proves the decision and the styles; device:themes' narrow pass proves the fit.
describe('the Reader dock in a narrow window (REA-58)', () => {
  const RN = require('react-native') as typeof import('react-native');
  let dims: jest.SpyInstance | undefined;
  const at = (width: number, fontScale: number) => {
    dims = jest.spyOn(RN, 'useWindowDimensions').mockReturnValue({ width, height: 800, scale: 3, fontScale });
  };
  afterEach(() => dims?.mockRestore());
  const flat = (st: unknown) => RN.StyleSheet.flatten(st as never) ?? {};
  const byTestID = (r: ReactTestRenderer.ReactTestRenderer, id: string) => r.root.findAll(n => n.props.testID === id)[0];
  const control = (r: ReactTestRenderer.ReactTestRenderer, label: string) =>
    r.root.find(n => n.props.accessibilityLabel === label && typeof n.props.onPress === 'function' && n.props.accessibilityRole !== undefined);
  const inside = (parent: { findAll: (f: (n: { props: { accessibilityLabel?: string } }) => boolean) => unknown[] }, label: string) =>
    parent.findAll(n => n.props.accessibilityLabel === label).length > 0;

  test.each([[360, 1], [360, 2], [448, 2]])('at %i dp and font scale %d the Stepper is on its own row', async (width, fontScale) => {
    at(width, fontScale);
    mockState.detail = text(['One.']);
    const r = await mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    expect(flat(byTestID(r, 'dock').props.style).flexDirection).toBe('column');
    const transport = byTestID(r, 'dock transport');
    for (const l of ['back paragraph', 'previous sentence', 'play', 'next sentence']) expect(inside(transport, l)).toBe(true);
    expect(inside(transport, 'faster')).toBe(false);
    expect(inside(byTestID(r, 'dock'), 'faster')).toBe(true);
  });

  test('at 448 dp and font scale 1.0 (the reference phone) the dock stays one row', async () => {
    at(448, 1);
    mockState.detail = text(['One.']);
    const r = await mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    expect(flat(byTestID(r, 'dock').props.style).flexDirection).toBe('row');
  });

  test('the one-row dock keeps a fixed gap between the transport and the Stepper', async () => {
    at(448, 1);
    mockState.detail = text(['One.']);
    const r = await mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    expect(flat(byTestID(r, 'dock').props.style).columnGap).toBe(8);
  });

  test('Play and Pause are the same width at font scale 2.0, so Pause does not move', async () => {
    at(360, 2);
    mockState.detail = text(['One two. Three four.']);
    const idle = await mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    const play = control(idle, 'play');
    expect(flat(play.props.style).minWidth).toBe(160);
    // The 40 dp pill's vertical slop makes the 48 dp target.
    expect(play.props.hitSlop).toEqual({ top: 4, bottom: 4 });
    (Native.getPlayback as jest.Mock).mockResolvedValueOnce({
      itemId: 1, playing: true, paragraphIndex: 0, start: 0, end: 8, rate: 2, engine: 'ready',
    });
    const playing = await mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    expect(flat(control(playing, 'pause').props.style).minWidth).toBe(160);
  });

  test('the rate buttons keep their 48 dp size and their steps (non-negotiable 9)', async () => {
    at(360, 2);
    mockState.detail = text(['One.']);
    const r = await mount(<ReaderScreen id={1} onTrim={() => {}} onGone={() => {}} />);
    expect(flat(control(r, 'faster').props.style)).toMatchObject({ width: 48, height: 48 });
    await press(r, 'faster');
    expect(Native.setRate).toHaveBeenLastCalledWith(2.1);
    await press(r, 'slower');
    expect(Native.setRate).toHaveBeenLastCalledWith(2);
  });
});

// REA-62: the long-press menu is the app's own bottom sheet, not the platform AlertDialog
// (One UI drew an empty area in it). Each action calls the same cut function as before.
describe('Trim long-press sheet (REA-62)', () => {
  const three = () => {
    mockState.detail = {
      item: { ...base, kind: 'text' },
      paragraphs: [{ kind: 'p', text: 'First.' }, { kind: 'p', text: 'Second.' }, { kind: 'p', text: 'Third.' }],
      cuts: [],
    };
  };
  const sheet = (r: ReactTestRenderer.ReactTestRenderer) => r.root.findByType(Modal);
  const pressId = (r: ReactTestRenderer.ReactTestRenderer, id: string) =>
    r.root.find(n => n.props.testID === id && typeof n.props.onPress === 'function').props.onPress();
  const longPress = async (r: ReactTestRenderer.ReactTestRenderer, id: string) => {
    await ReactTestRenderer.act(async () => {
      r.root.find(n => n.props.testID === id && typeof n.props.onLongPress === 'function').props.onLongPress();
    });
  };
  const lastCuts = () => {
    const calls = (Native.setCuts as jest.Mock).mock.calls;
    return calls[calls.length - 1];
  };

  test('a long press opens the sheet with its three actions and never the platform Alert', async () => {
    three();
    const alert = jest.spyOn(Alert, 'alert');
    const r = await mount(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
    expect(sheet(r).props.visible).toBe(false);
    await longPress(r, 'paragraph 2');
    expect(sheet(r).props.visible).toBe(true);
    const labels = sheet(r).findAll(n => typeof n.props.testID === 'string' && n.props.testID.startsWith('trim menu')).map(n => n.props.accessibilityLabel);
    expect(labels).toEqual(expect.arrayContaining(['Start here', 'Cut everything after this', 'Cancel']));
    expect(alert).not.toHaveBeenCalled();
    alert.mockRestore();
  });

  test('Cut everything after this cuts the paragraphs after the pressed one and closes', async () => {
    three();
    (Native.setCuts as jest.Mock).mockClear();
    const r = await mount(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
    await longPress(r, 'paragraph 1');
    await ReactTestRenderer.act(async () => pressId(r, 'trim menu cut after'));
    expect(lastCuts()).toEqual([1, [1, 2]]);
    expect(sheet(r).props.visible).toBe(false);
  });

  test('Start here cuts the paragraphs before the pressed one and closes', async () => {
    three();
    (Native.setCuts as jest.Mock).mockClear();
    const r = await mount(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
    await longPress(r, 'paragraph 3');
    await ReactTestRenderer.act(async () => pressId(r, 'trim menu start here'));
    expect(lastCuts()).toEqual([1, [0, 1]]);
    expect(sheet(r).props.visible).toBe(false);
  });

  test('Cancel, the scrim and Back close the sheet and change nothing', async () => {
    three();
    (Native.setCuts as jest.Mock).mockClear();
    const r = await mount(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
    for (const how of ['cancel', 'scrim', 'back']) {
      await longPress(r, 'paragraph 2');
      expect(sheet(r).props.visible).toBe(true);
      await ReactTestRenderer.act(async () => {
        if (how === 'cancel') pressId(r, 'trim menu cancel');
        else if (how === 'scrim') r.root.find(n => n.props.accessibilityLabel === 'close trim menu').props.onPress();
        else sheet(r).props.onRequestClose();
      });
      expect(sheet(r).props.visible).toBe(false);
    }
    expect(Native.setCuts).not.toHaveBeenCalled();
  });

  test('two row taps before a re-render apply only the first action', async () => {
    three();
    (Native.setCuts as jest.Mock).mockClear();
    const r = await mount(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
    await longPress(r, 'paragraph 2');
    await ReactTestRenderer.act(async () => {
      pressId(r, 'trim menu start here');
      pressId(r, 'trim menu cut after');
    });
    expect(Native.setCuts).toHaveBeenCalledTimes(1);
    expect(lastCuts()).toEqual([1, [0]]);
  });

  // At 360 dp and font scale 2.0 (REA-58's narrow case) "Cut everything after this" may wrap.
  // A row of fixed height would clip a second line, so each row's style must let it grow: a
  // 48 dp minimum, no fixed height, and a label not capped below the font scale Android asks
  // for. The test renderer has no layout, so the growth itself is checked on the phone
  // (device:themes' narrow pass, scripts/sheet-check.py).
  describe('row style', () => {
    const RN = require('react-native') as typeof import('react-native');
    test.each(['trim menu start here', 'trim menu cut after', 'trim menu cancel'])(
      "the %s row's style lets it grow: minHeight 48, no fixed height",
      async id => {
        three();
        const r = await mount(<TrimScreen id={1} onDone={() => {}} onGone={() => {}} />);
        await longPress(r, 'paragraph 2');
        const row = r.root.find(n => n.props.testID === id && typeof n.props.onPress === 'function');
        const style = typeof row.props.style === 'function' ? row.props.style({ pressed: false }) : row.props.style;
        const flat = RN.StyleSheet.flatten(style) ?? {};
        expect(flat.height).toBeUndefined();
        expect(flat.minHeight).toBe(48);
        expect(flat.paddingVertical ?? flat.paddingTop ?? 0).toBeGreaterThan(0);
        for (const t of row.findAllByType(RN.Text)) {
          expect(t.props.numberOfLines).toBeUndefined();
          expect(t.props.maxFontSizeMultiplier).toBeUndefined();
        }
      },
    );
  });
});
