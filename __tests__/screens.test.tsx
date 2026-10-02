import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import type { NativeItem } from '../src/native/NativeReadMeSpeech';

const mockState = {
  items: [] as NativeItem[],
  detail: null as unknown,
  engine: { status: 'ready', voices: [] as unknown[], selected: null as string | null },
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
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

import { ListScreen } from '../src/ui/ListScreen';
import { TrimScreen } from '../src/ui/TrimScreen';
import { ReaderScreen } from '../src/ui/ReaderScreen';
import { SettingsScreen } from '../src/ui/SettingsScreen';

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

async function render(el: React.ReactElement) {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(el);
  });
  return strings(r.toJSON()).join('\n');
}

beforeEach(() => {
  mockState.items = [];
  mockState.detail = null;
  mockState.engine = { status: 'ready', voices: [], selected: null };
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
