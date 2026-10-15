/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import { AppState, BackHandler, DeviceEventEmitter } from 'react-native';

const TITLES: Record<number, string> = { 1: 'Item one', 2: 'Item two', 3: 'Item three' };
const mockState = {
  // The service's state as getPlayback reports it.
  playback: { itemId: null as number | null, playing: false, waitingItemId: null as number | null },
};
const mockSnapshot = (itemId: number | null, playing: boolean, waitingItemId: number | null = null) => ({
  itemId,
  waitingItemId,
  playing,
  paragraphIndex: itemId === null ? -1 : 0,
  start: 0,
  end: itemId === null ? 0 : 11,
  rate: 2,
  engine: 'ready',
});
const mockItem = (id: number) => ({
  id, kind: 'text', url: null, title: TITLES[id], site: null, byline: null, createdAt: 10 - id,
  state: 'ready', failReason: null, openedAt: 5, archivedAt: null, words: 4, progress: 0,
});

jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    listItems: jest.fn(async () => [1, 2, 3].map(mockItem)),
    getItem: jest.fn(async (id: number) => ({
      item: mockItem(id),
      paragraphs: [{ kind: 'p', text: 'Alpha here. Beta here.' }],
      cuts: [],
    })),
    getBody: jest.fn(async () => null),
    completeExtraction: jest.fn(async () => true),
    markOpened: jest.fn(async () => true),
    setCuts: jest.fn(async () => undefined),
    getRate: jest.fn(async () => 2),
    getPosition: jest.fn(async () => null),
    play: jest.fn(async () => true),
    pause: jest.fn(async () => true),
    resume: jest.fn(async () => true),
    stop: jest.fn(async () => true),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
    getEngine: jest.fn(async () => ({ status: 'ready', voices: [], selected: null, engine: null, engineLabel: null, choosable: false, engines: [] })),
    getPlayback: jest.fn(async () =>
      mockSnapshot(mockState.playback.itemId, mockState.playback.playing, mockState.playback.waitingItemId),
    ),
  },
}));
// SafeAreaProvider renders nothing until native insets arrive, which never happens in Jest.
jest.mock('react-native-safe-area-context', () => ({
  SafeAreaProvider: ({ children }: { children: unknown }) => children,
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));
import Native from '../src/native/NativeReadMeSpeech';
import App from '../App';

type Node = ReactTestRenderer.ReactTestRendererNode | ReactTestRenderer.ReactTestRendererNode[] | null;
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

let backHandlers: (() => unknown)[] = [];
let appStateHandler: ((s: string) => void) | null = null;
const mounted: ReactTestRenderer.ReactTestRenderer[] = [];

beforeEach(() => {
  jest.clearAllMocks();
  mockState.playback = { itemId: null, playing: false, waitingItemId: null };
  backHandlers = [];
  appStateHandler = null;
  jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_e, h) => {
    const f = h as () => unknown;
    backHandlers.push(f);
    return { remove: () => { backHandlers = backHandlers.filter(x => x !== f); } };
  });
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_e, h) => {
    appStateHandler = h as (s: string) => void;
    return { remove: () => { appStateHandler = null; } } as ReturnType<typeof AppState.addEventListener>;
  });
});
afterEach(async () => {
  await ReactTestRenderer.act(async () => {
    while (mounted.length) mounted.pop()!.unmount();
  });
  jest.restoreAllMocks();
});

const flush = () => ReactTestRenderer.act(async () => { await new Promise(r => setTimeout(r, 0)); });

async function render(): Promise<ReactTestRenderer.ReactTestRenderer> {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<App />);
  });
  mounted.push(r);
  await flush();
  return r;
}
const shown = (r: ReactTestRenderer.ReactTestRenderer) => strings(r.toJSON());

async function press(r: ReactTestRenderer.ReactTestRenderer, find: (n: ReactTestRenderer.ReactTestInstance) => boolean) {
  const node = r.root.findAll(n => typeof n.props.onPress === 'function' && find(n))[0];
  await ReactTestRenderer.act(async () => node.props.onPress());
  await flush();
}
const openRow = (r: ReactTestRenderer.ReactTestRenderer, id: number) =>
  press(r, n => n.props.accessibilityLabel === undefined && n.findAll(c => c.props.children === TITLES[id]).length > 0);
const pressLabel = (r: ReactTestRenderer.ReactTestRenderer, label: string) =>
  press(r, n => n.props.accessibilityLabel === label);

/** The service emits a snapshot (and getPlayback reports it from then on). */
async function service(itemId: number | null, playing: boolean, waitingItemId: number | null = null) {
  mockState.playback = { itemId, playing, waitingItemId };
  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit('ReadMePlayback', mockSnapshot(itemId, playing, waitingItemId));
  });
  await flush();
}
async function back() {
  await ReactTestRenderer.act(async () => {
    backHandlers[backHandlers.length - 1]();
  });
  await flush();
}
const readerOf = (r: ReactTestRenderer.ReactTestRenderer) => {
  const s = shown(r);
  return { title: [1, 2, 3].find(i => s.includes(TITLES[i]) && s.includes('"trim"')), s };
};

test('renders correctly', async () => {
  const r = await render();
  expect(shown(r)).toContain('"settings"');
});

describe('the Reader follows a continuous-play handover (REA-55)', () => {
  test('a visible Reader switches to the next item, highlighted, with Pause', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    expect(readerOf(r).title).toBe(1);
    await service(2, true);
    const { title, s } = readerOf(r);
    expect(title).toBe(2);
    expect(s).toContain('"current paragraph"');
    expect(s).toContain('"pause"');
    // Following is not an open (ADR 0011): only the user's own open marks an item opened.
    expect(Native.markOpened).toHaveBeenCalledTimes(1);
    expect(Native.markOpened).toHaveBeenCalledWith(1);
    // AGENTS.md 11: following changes a route, never playback.
    expect(Native.play).not.toHaveBeenCalled();
    expect(Native.pause).not.toHaveBeenCalled();
    expect(Native.resume).not.toHaveBeenCalled();
  });

  test('Pause in the followed Reader pauses the item being read', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    await service(2, true);
    await pressLabel(r, 'pause');
    expect(Native.pause).toHaveBeenCalledTimes(1);
    expect(Native.play).not.toHaveBeenCalled();
  });

  test('a handover while Trim is open over the Reader returns to the new item on Done', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    await pressLabel(r, 'trim');
    expect(shown(r)).toContain('"trim done"');
    await service(2, true);
    await pressLabel(r, 'trim done');
    expect(readerOf(r).title).toBe(2);
  });

  test('a handover missed while away is followed when the app is active again', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    mockState.playback = { itemId: 3, playing: true, waitingItemId: null };
    await ReactTestRenderer.act(async () => appStateHandler!('active'));
    await flush();
    expect(readerOf(r).title).toBe(3);
  });

  test('Back from the followed Reader goes to the List', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    await service(2, true);
    await back();
    expect(shown(r)).toContain('"settings"');
    expect(shown(r)).not.toContain('"trim"');
  });

  test('a Reader opened while another item plays does not switch on that item\'s handover', async () => {
    mockState.playback = { itemId: 2, playing: true, waitingItemId: null };
    const r = await render();
    await openRow(r, 1);
    await service(3, true);
    expect(readerOf(r).title).toBe(1);
  });

  test('Play on a Reader opened while another item plays keeps that Reader', async () => {
    mockState.playback = { itemId: 2, playing: true, waitingItemId: null };
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    expect(readerOf(r).title).toBe(1);
  });

  test('an item ending with no next item leaves the Reader where it is', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    await service(null, false);
    expect(readerOf(r).title).toBe(1);
  });

  test('a Play waiting for the engine is not a handover', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true, 2);
    await service(2, true);
    expect(readerOf(r).title).toBe(1);
  });
});
