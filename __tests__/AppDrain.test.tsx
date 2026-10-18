/**
 * @format
 * REA-50: App drains fetched items (extraction) whatever screen is open.
 */

import React from 'react';
import { DeviceEventEmitter } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import type { NativeItem } from '../src/native/NativeReadMeSpeech';

// jest.mock factories may only reference variables whose names start with "mock".
// The store's state, so a completed extraction leaves the fetched list as the real store does.
const mockRows: (NativeItem & { body: string | null })[] = [];

jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    listItems: jest.fn(async () => mockRows.map(({ body: _b, ...r }) => ({ ...r }))),
    listFetched: jest.fn(async () =>
      mockRows.filter(r => r.state === 'fetched').map(({ body: _b, ...r }) => ({ ...r })),
    ),
    getBody: jest.fn(async (id: number) => mockRows.find(r => r.id === id)?.body ?? null),
    completeExtraction: jest.fn(async (id: number) => {
      const r = mockRows.find(x => x.id === id);
      if (r) r.state = 'ready';
      return true;
    }),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
    getEngine: jest.fn(async () => ({ status: 'ready', voices: [], selected: null, engine: null, engineLabel: null, choosable: false, engines: [] })),
    getPlayback: jest.fn(async () => ({
      itemId: null, playing: false, paragraphIndex: -1, start: 0, end: 0, rate: 2, engine: 'unknown',
    })),
    getRate: jest.fn(async () => 2),
    getBridge: jest.fn(async () => ({ enabled: false, state: 'off', port: 8787, token: null, error: null })),
    getContinuousPlay: jest.fn(async () => false),
    readerShows: jest.fn(),
  },
}));
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));
import '../jest/loadReactNative';
import Native from '../src/native/NativeReadMeSpeech';
import App from '../App';

const fetched = (id: number): NativeItem & { body: string | null } => ({
  id, kind: 'link', url: `https://example.com/${id}`, title: 'example.com', site: null, byline: null,
  createdAt: id, state: 'fetched', failReason: null, openedAt: null, archivedAt: null, words: 0, progress: 0,
  body: '<html><body><article><p>Some words here.</p></article></body></html>',
});

const mounted: ReactTestRenderer.ReactTestRenderer[] = [];
beforeEach(() => {
  mockRows.length = 0;
  (Native.completeExtraction as jest.Mock).mockClear();
});
afterEach(async () => {
  await ReactTestRenderer.act(async () => {
    while (mounted.length) mounted.pop()!.unmount();
  });
});

async function renderApp() {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<App />);
  });
  mounted.push(r);
  return r;
}

// Lets the drain's chained promises settle.
const settle = () => ReactTestRenderer.act(async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
});

test('renders correctly', async () => {
  await renderApp();
});

test('a fetched item is extracted while Settings is open (REA-50)', async () => {
  const r = await renderApp();
  await ReactTestRenderer.act(async () => {
    r.root.find(n => n.props.accessibilityLabel === 'settings' && typeof n.props.onPress === 'function').props.onPress();
  });
  await settle();
  mockRows.push(fetched(5));
  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit('ReadMeItemsChanged');
  });
  await settle();
  expect((Native.completeExtraction as jest.Mock).mock.calls.map(c => c[0])).toEqual([5]);
});

test('on the List, one change event extracts a fetched item once', async () => {
  await renderApp();
  await settle();
  mockRows.push(fetched(6));
  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit('ReadMeItemsChanged');
  });
  await settle();
  expect((Native.completeExtraction as jest.Mock).mock.calls.map(c => c[0])).toEqual([6]);
});
