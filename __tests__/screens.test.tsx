import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type { NativeItem } from '../src/native/NativeReadMeSpeech';

const mockState = {
  items: [] as NativeItem[],
  detail: null as unknown,
  engine: { status: 'ready', voices: [] as unknown[], selected: null as string | null },
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
  mockState.engine = { status: 'ready', voices: [], selected: null };
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
  mockState.engine = { status: 'no-voice', voices: [], selected: null };
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
    voices: [{ name: 'en-us-x-a-local', language: 'en', quality: 400 }],
    selected: 'en-us-x-a-local',
  };
  mockState.items = [base, { ...base, id: 2, archivedAt: 3 }];
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('en-us-x-a-local');
  expect(out).toContain('2.0x');
  expect(out).toContain('2 items, 1 archived');
  expect(out).toContain('Licenses');
});

test('Settings shows the bridge off, with no token (R-M12)', async () => {
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('Obsidian bridge');
  expect(out).toContain('Off');
  expect(out).toContain('"bridge on"');
  expect(out).not.toContain('pairing token');
});

test('Settings shows the token with Copy and New token while the bridge is on', async () => {
  mockState.bridge = { enabled: true, state: 'on', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('On at 127.0.0.1:8787');
  expect(out).toContain('0123456789abcdef0123456789abcdef');
  expect(out).toContain('"copy token"');
  expect(out).toContain('"new token"');
  expect(out).toContain('"bridge off"');
});

test('turning the bridge on goes through the module', async () => {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<SettingsScreen onLicenses={() => {}} />);
  });
  mounted.push(r);
  await ReactTestRenderer.act(async () => {
    r.root.find(n => n.props.accessibilityLabel === 'bridge on' && typeof n.props.onPress === 'function').props.onPress();
  });
  expect(Native.setBridgeEnabled).toHaveBeenCalledWith(true);
  expect(strings(r.toJSON()).join('\n')).toContain('0123456789abcdef0123456789abcdef');
});

test('turning the bridge on with notifications refused says the notification is hidden', async () => {
  const spy = jest.spyOn(bridgeLib, 'ensureNotifications').mockResolvedValue(false);
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<SettingsScreen onLicenses={() => {}} />);
  });
  mounted.push(r);
  await ReactTestRenderer.act(async () => {
    r.root.find(n => n.props.accessibilityLabel === 'bridge on' && typeof n.props.onPress === 'function').props.onPress();
  });
  expect(spy).toHaveBeenCalled();
  expect(Native.setBridgeEnabled).toHaveBeenCalledWith(true);
  expect(strings(r.toJSON()).join('\n')).toContain('Notifications are off for Read Me');
  spy.mockRestore();
});
