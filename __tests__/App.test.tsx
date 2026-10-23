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
  // Items never opened by the user (continuous play keeps opened_at NULL): their open shows Trim.
  unopened: [] as number[],
  // REA-63: item states other than ready, and the ids takeSharedOpen hands out, once each.
  states: {} as Record<number, string>,
  shared: [] as number[],
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
  state: mockState.states[id] ?? 'ready', failReason: null, openedAt: mockState.unopened.includes(id) ? null : 5, archivedAt: null, words: 4, progress: 0,
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
    listFetched: jest.fn(async () => []),
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
    readerShows: jest.fn(),
    takeSharedOpen: jest.fn(async () => mockState.shared.shift() ?? null),
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
import '../jest/loadReactNative';
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
    // REA-50: the current paragraph is found by its testID (TalkBack reads its own text).
    const id = node.props.testID;
    if (typeof id === 'string') out.push(`#${id}`);
    strings(node.children, out);
  }
  return out;
}

let backHandlers: (() => unknown)[] = [];
// REA-56: App and the Reader both listen, so every handler hears each change.
let appStateHandlers: ((s: string) => void)[] = [];
const appStateHandler = (s: string) => appStateHandlers.slice().forEach(h => h(s));
// The preset's AppState.currentState is a jest.fn, not a state; the Reader reads it on mount.
let currentState: PropertyDescriptor | undefined;
const mounted: ReactTestRenderer.ReactTestRenderer[] = [];
// Every act() the helpers start, until it settles. A test that times out leaves its act and the
// rest of its body running: afterEach closes the helpers to it (an act it starts later throws)
// and waits for what it has started, so the next test never runs inside it ("overlapping act()
// calls", ci run 38776437438) and its tree is unmounted. That holds for an act that settles
// within afterEach's own 5 s; one that never settles fails every later test at afterEach, loudly.
// A rejected act fails its test in afterEach even if nothing awaited it.
const pending = new Set<Promise<unknown>>();
let rejected: unknown[] = [];
let closed = false;
function act(fn: () => unknown): Promise<unknown> {
  if (closed) return Promise.reject(new Error('act() after the test ended'));
  const p = Promise.resolve(ReactTestRenderer.act(async () => { await fn(); }));
  pending.add(p);
  p.then(
    () => { pending.delete(p); },
    e => { pending.delete(p); rejected.push(e); },
  );
  return p;
}

beforeEach(() => {
  closed = false;
  rejected = [];
  jest.clearAllMocks();
  mockState.playback = { itemId: null, playing: false, waitingItemId: null };
  mockState.unopened = [];
  mockState.states = {};
  mockState.shared = [];
  backHandlers = [];
  appStateHandlers = [];
  currentState = Object.getOwnPropertyDescriptor(AppState, 'currentState');
  Object.defineProperty(AppState, 'currentState', { value: 'active', configurable: true, writable: true });
  jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_e, h) => {
    const f = h as () => unknown;
    backHandlers.push(f);
    return { remove: () => { backHandlers = backHandlers.filter(x => x !== f); } };
  });
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_e, h) => {
    const f = h as (s: string) => void;
    appStateHandlers.push(f);
    return { remove: () => { appStateHandlers = appStateHandlers.filter(x => x !== f); } } as ReturnType<typeof AppState.addEventListener>;
  });
});
afterEach(async () => {
  closed = true;
  while (pending.size) await Promise.allSettled([...pending]);
  await ReactTestRenderer.act(async () => {
    while (mounted.length) mounted.pop()!.unmount();
  });
  jest.restoreAllMocks();
  if (currentState) Object.defineProperty(AppState, 'currentState', currentState);
  if (rejected.length) throw rejected[0];
});

const flush = () => act(async () => { await new Promise(r => setTimeout(r, 0)); });

async function render(): Promise<ReactTestRenderer.ReactTestRenderer> {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await act(() => {
    r = ReactTestRenderer.create(<App />);
    mounted.push(r);
  });
  await flush();
  return r;
}
const shown = (r: ReactTestRenderer.ReactTestRenderer) => strings(r.toJSON());

async function press(r: ReactTestRenderer.ReactTestRenderer, find: (n: ReactTestRenderer.ReactTestInstance) => boolean) {
  const node = r.root.findAll(n => typeof n.props.onPress === 'function' && find(n))[0];
  if (!node) throw new Error('nothing on screen to press');
  await act(async () => node.props.onPress());
  await flush();
}
const openRow = (r: ReactTestRenderer.ReactTestRenderer, id: number) =>
  press(r, n => n.props.accessibilityLabel === undefined && n.findAll(c => c.props.children === TITLES[id]).length > 0);
const pressLabel = (r: ReactTestRenderer.ReactTestRenderer, label: string) =>
  press(r, n => n.props.accessibilityLabel === label);

/** The service emits a snapshot (and getPlayback reports it from then on). */
async function service(itemId: number | null, playing: boolean, waitingItemId: number | null = null) {
  mockState.playback = { itemId, playing, waitingItemId };
  await act(async () => {
    DeviceEventEmitter.emit('ReadMePlayback', mockSnapshot(itemId, playing, waitingItemId));
  });
  await flush();
}
async function back() {
  await act(async () => {
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
    expect(s).toContain('#current paragraph');
    expect(s).toContain('"pause"');
    // Following is not an open (ADR 0011): only the user's own open marks an item opened.
    expect(Native.markOpened).toHaveBeenCalledTimes(1);
    expect(Native.markOpened).toHaveBeenCalledWith(1);
    // AGENTS.md 11: following changes a route, never playback.
    expect(Native.play).not.toHaveBeenCalled();
    expect(Native.pause).not.toHaveBeenCalled();
    expect(Native.resume).not.toHaveBeenCalled();
  });

  test('the followed Reader writes the new item and that it plays, once it shows it (REA-56)', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    expect(Native.readerShows).toHaveBeenLastCalledWith(1, true);
    (Native.readerShows as jest.Mock).mockClear();
    await service(2, true);
    expect(readerOf(r).title).toBe(2);
    expect(Native.readerShows).toHaveBeenCalledTimes(1);
    expect(Native.readerShows).toHaveBeenCalledWith(2, true);
    await service(2, true); // the next sentence writes nothing
    expect(Native.readerShows).toHaveBeenCalledTimes(1);
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
    await act(async () => appStateHandler('active'));
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

  test('Play on a Reader opened while another item plays plays that item and keeps that Reader', async () => {
    mockState.playback = { itemId: 2, playing: true, waitingItemId: null };
    const r = await render();
    await openRow(r, 1);
    await pressLabel(r, 'play');
    expect(Native.play).toHaveBeenCalledTimes(1);
    expect((Native.play as jest.Mock).mock.calls[0][0]).toBe(1);
    await service(1, true);
    expect(readerOf(r).title).toBe(1);
  });

  // Fix round 1 (critic 2 F3): a continuous-played item's first open shows Trim; a handover
  // while it is open must not leave Done on a Reader of the finished item.
  test('a handover during the first-open Trim of the item being read: Done opens the item being read', async () => {
    mockState.unopened = [1];
    mockState.playback = { itemId: 1, playing: true, waitingItemId: null };
    const r = await render();
    await openRow(r, 1);
    expect(shown(r)).toContain('"trim done"');
    await service(2, true);
    expect(shown(r)).toContain('"trim done"'); // Trim stays on the item being trimmed
    await pressLabel(r, 'trim done');
    const { title, s: seen } = readerOf(r);
    expect(title).toBe(2);
    expect(seen).toContain('"pause"');
    await pressLabel(r, 'pause');
    expect(Native.play).not.toHaveBeenCalled();
    expect(Native.pause).toHaveBeenCalledTimes(1);
  });

  test('a first-open Trim of an item not being read opens its own Reader after a handover elsewhere', async () => {
    mockState.unopened = [3];
    mockState.playback = { itemId: 1, playing: true, waitingItemId: null };
    const r = await render();
    await openRow(r, 3);
    await service(2, true);
    await pressLabel(r, 'trim done');
    expect(readerOf(r).title).toBe(3);
  });

  // Critic F3/F4: the mount refresh and the seq guard are pinned.
  test('a handover is followed even when the app mounted while the item was already playing', async () => {
    mockState.playback = { itemId: 1, playing: true, waitingItemId: null };
    const r = await render();
    await openRow(r, 1);
    await service(2, true); // the first event after mount is already the handover
    expect(readerOf(r).title).toBe(2);
  });

  test('a getPlayback answer older than a handover event does not move the Reader back', async () => {
    const r = await render();
    await openRow(r, 1);
    await service(1, true);
    let answer!: (v: unknown) => void;
    (Native.getPlayback as jest.Mock).mockImplementationOnce(() => new Promise(res => { answer = res; }));
    await act(async () => appStateHandler('active'));
    await service(2, true);
    expect(readerOf(r).title).toBe(2);
    await act(async () => answer(mockSnapshot(1, true)));
    await flush();
    expect(readerOf(r).title).toBe(2);
    await service(2, true); // B's next sentence: no handover from a stale A
    expect(readerOf(r).title).toBe(2);
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

describe('a share that matched an unread item (REA-63, ADR 0016)', () => {
  const noTransport = () => {
    expect(Native.play).not.toHaveBeenCalled();
    expect(Native.pause).not.toHaveBeenCalled();
    expect(Native.resume).not.toHaveBeenCalled();
  };
  const isTrim = (r: ReactTestRenderer.ReactTestRenderer) => shown(r).includes('"trim done"');
  const isList = (r: ReactTestRenderer.ReactTestRenderer) => shown(r).includes('"settings"');

  test('on a cold start, a never-opened ready item opens Trim and is marked opened', async () => {
    mockState.unopened = [2];
    mockState.shared = [2];
    const r = await render();
    expect(isTrim(r)).toBe(true);
    expect(Native.getItem).toHaveBeenCalledWith(2);
    expect(Native.markOpened).toHaveBeenCalledWith(2);
    await back();
    expect(isList(r)).toBe(true);
    noTransport();
  });

  test('an opened item opens its Reader and records no open', async () => {
    mockState.shared = [3];
    const r = await render();
    expect(readerOf(r).title).toBe(3);
    expect(Native.markOpened).not.toHaveBeenCalled();
    noTransport();
  });

  test('a fetch-failed item shows the unread List with its Retry, and opens nothing', async () => {
    mockState.states = { 2: 'fetch-failed' };
    const r = await render();
    await pressLabel(r, 'archive');
    expect(shown(r)).toContain('"unread"');
    mockState.shared = [2];
    await act(async () => {
      DeviceEventEmitter.emit('ReadMeSharedOpen');
    });
    await flush();
    const s = shown(r);
    expect(s).toContain('"archive"'); // the unread tab, whichever tab was showing
    expect(s).toContain(`"retry ${TITLES[2]}"`);
    expect(Native.markOpened).not.toHaveBeenCalled();
    noTransport();
  });

  test('a share taken on return to the foreground replaces the open screen', async () => {
    const r = await render();
    await openRow(r, 2);
    expect(readerOf(r).title).toBe(2);
    mockState.unopened = [1];
    mockState.shared = [1];
    await act(async () => appStateHandler('active'));
    await flush();
    expect(isTrim(r)).toBe(true);
    expect(Native.markOpened).toHaveBeenLastCalledWith(1);
    await back();
    expect(isList(r)).toBe(true); // the Reader it replaced is gone
    noTransport();
  });

  test('the event opens the item while Read Me is in front, and only once', async () => {
    const r = await render();
    mockState.shared = [3];
    await act(async () => {
      DeviceEventEmitter.emit('ReadMeSharedOpen');
    });
    await flush();
    expect(readerOf(r).title).toBe(3);
    await back();
    await act(async () => appStateHandler('active'));
    await flush();
    expect(isList(r)).toBe(true);
    noTransport();
  });

  test('an item that cannot be read opens the Reader, which says so, and records no open', async () => {
    mockState.unopened = [2];
    mockState.shared = [2];
    const getItem = Native.getItem as jest.Mock;
    const real = getItem.getMockImplementation()!;
    getItem.mockImplementation(async () => {
      throw new Error('disk');
    });
    try {
      const r = await render();
      expect(shown(r)).toContain('Could not open this item.');
      expect(Native.markOpened).not.toHaveBeenCalled();
      noTransport();
    } finally {
      getItem.mockImplementation(real); // clearAllMocks keeps implementations
    }
  });

  test('of two opens close together, the newer stays on top even when the older answers last', async () => {
    const r = await render();
    let answerOlder!: () => void;
    const real = (Native.getItem as jest.Mock).getMockImplementation()!;
    (Native.getItem as jest.Mock).mockImplementationOnce(
      (id: number) => new Promise(res => { answerOlder = () => res(real(id)); }),
    );
    mockState.unopened = [2];
    mockState.shared = [2];
    await act(async () => {
      DeviceEventEmitter.emit('ReadMeSharedOpen');
    });
    await flush();
    mockState.shared = [3];
    await act(async () => {
      DeviceEventEmitter.emit('ReadMeSharedOpen');
    });
    await flush();
    expect(readerOf(r).title).toBe(3);
    await act(async () => answerOlder());
    await flush();
    expect(readerOf(r).title).toBe(3);
    expect(isTrim(r)).toBe(false);
    expect(Native.markOpened).not.toHaveBeenCalled();
    noTransport();
  });

  test('nothing pending changes nothing', async () => {
    const r = await render();
    await act(async () => appStateHandler('active'));
    await flush();
    expect(isList(r)).toBe(true);
    expect(Native.getItem).not.toHaveBeenCalled();
    expect(Native.markOpened).not.toHaveBeenCalled();
  });
});
