import type { Item } from '../src/library/library';
import {
  actions,
  back,
  badge,
  formatRate,
  guidance,
  openRoute,
  parseNotices,
  push,
  lineTop,
  readerParagraphs,
  stepRate,
  subtitle,
  trimDone,
  visibleItems,
  type Route,
  bridgeStatus,
  parsePort,
} from '../src/ui/model';

const item = (over: Partial<Item> = {}): Item => ({
  id: 1,
  kind: 'link',
  title: 'T',
  createdAt: 1,
  state: 'ready',
  words: 0,
  progress: 0,
  ...over,
});

describe('routes (Review Focus 5)', () => {
  const list: Route = { name: 'list' };

  test('back pops, and leaves the app only from the list', () => {
    const s = push(push([list], { name: 'reader', id: 1 }), { name: 'trim', id: 1 });
    expect(back(s)).toEqual([list, { name: 'reader', id: 1 }]);
    expect(back([list])).toBeNull();
  });

  test('Trim Done returns to the Reader it came from', () => {
    const fromReader = [list, { name: 'reader', id: 1 } as Route, { name: 'trim', id: 1 } as Route];
    expect(trimDone(fromReader, 1)).toEqual([list, { name: 'reader', id: 1 }]);
  });

  test('Trim Done after a first open replaces Trim with the Reader', () => {
    expect(trimDone([list, { name: 'trim', id: 1 }], 1)).toEqual([list, { name: 'reader', id: 1 }]);
  });

  test('the first open goes to Trim, later opens to the Reader (R-M05)', () => {
    expect(openRoute(item())).toEqual({ name: 'trim', id: 1 });
    expect(openRoute(item({ openedAt: 5 }))).toEqual({ name: 'reader', id: 1 });
  });
});

describe('the list (R-M01, R-M10)', () => {
  test('unread and archive are separate views', () => {
    const items = [item({ id: 1 }), item({ id: 2, archivedAt: 9 })];
    expect(visibleItems(items, false).map(i => i.id)).toEqual([1]);
    expect(visibleItems(items, true).map(i => i.id)).toEqual([2]);
  });

  test('each state offers the R-M10 actions', () => {
    expect(actions(item({ state: 'fetch-failed' }))).toEqual(['retry', 'delete']);
    expect(actions(item({ state: 'extract-poor' }))).toEqual(['open', 'delete']);
    expect(actions(item({ state: 'ready' }))).toEqual(['open', 'delete']);
    expect(actions(item({ state: 'fetching' }))).toEqual(['delete']);
    expect(actions(item({ state: 'fetched' }))).toEqual(['delete']);
    expect(actions(item({ archivedAt: 3 }))).toEqual(['open', 'restore', 'delete']);
  });

  test('failed and poor items carry the share-the-text guidance', () => {
    expect(guidance(item({ state: 'fetch-failed' }))).toMatch(/share the text/);
    expect(guidance(item({ state: 'extract-poor' }))).toMatch(/share the text/);
    expect(guidance(item())).toBeNull();
  });

  test('subtitle: site or Shared text, words, progress', () => {
    expect(subtitle(item({ site: 'BBC', words: 1234, progress: 0.42 }))).toBe('BBC · 1234 words · 42% read');
    expect(subtitle(item({ kind: 'text', words: 1 }))).toBe('Shared text · 1 word');
    expect(subtitle(item({ progress: 1 }))).toBe('');
  });

  test('the badge is the state, with the reason when there is one', () => {
    expect(badge(item({ state: 'fetch-failed', failReason: 'offline' }))).toBe('fetch-failed: offline');
    expect(badge(item())).toBe('ready');
  });
});

describe('the reader', () => {
  const paragraphs = [
    { kind: 'p' as const, text: 'A one. A two.' },
    { kind: 'p' as const, text: 'B one.' },
    { kind: 'p' as const, text: 'C one.' },
  ];

  test('only kept paragraphs, with the current sentence marked', () => {
    const r = readerParagraphs(paragraphs, [1], { paragraphIndex: 0, start: 7, end: 13 });
    expect(r.map(p => p.index)).toEqual([0, 2]);
    expect(r[0].current).toEqual({ start: 7, end: 13 });
    expect(r[1].current).toBeNull();
  });

  test('a sentence in a cut paragraph marks nothing', () => {
    expect(readerParagraphs(paragraphs, [1], { paragraphIndex: 1, start: 0, end: 6 }).every(p => p.current === null)).toBe(true);
  });

  test('rate steps by 0.1 within 0.5..4.0', () => {
    expect(stepRate(2, 1)).toBe(2.1);
    expect(stepRate(0.5, -1)).toBe(0.5);
    expect(stepRate(4, 1)).toBe(4);
    expect(stepRate(1.3, -1)).toBe(1.2);
    expect(formatRate(2)).toBe('2.0x');
  });
});

test('notices parse from the asset and sort by name', () => {
  const json = JSON.stringify({
    npm: [{ name: 'zeta', version: '1', license: 'MIT', text: 'Z' }],
    android: [{ name: 'androidx.core:core', version: '1.13.1', license: 'Apache-2.0', url: 'https://x' }],
  });
  expect(parseNotices(json).map(n => n.name)).toEqual(['androidx.core:core', 'zeta']);
  expect(parseNotices('not json')).toEqual([]);
});

test('lineTop finds the laid-out line holding a character offset', () => {
  // /critique run A F1: the Reader scrolls to the highlight's line inside a long paragraph.
  const lines = [
    { text: 'one two ', y: 0 },
    { text: 'three four ', y: 30 },
    { text: 'five', y: 60 },
  ];
  expect(lineTop(lines, 0)).toBe(0);
  expect(lineTop(lines, 8)).toBe(30);
  expect(lineTop(lines, 19)).toBe(60);
  expect(lineTop(lines, 999)).toBe(60);
  expect(lineTop([], 5)).toBe(0);
});

test('parsePort accepts 1024 to 65535 only', () => {
  expect(parsePort('8787')).toBe(8787);
  expect(parsePort(' 1024 ')).toBe(1024);
  expect(parsePort('65535')).toBe(65535);
  for (const bad of ['', '80', '1023', '65536', '87.87', '-1', 'abc', '8787a', '0x2253']) {
    expect(parsePort(bad)).toBeNull();
  }
});

test('bridgeStatus explains a taken port', () => {
  const b = { enabled: true, state: 'on', port: 8787, token: 'x', error: null };
  expect(bridgeStatus({ ...b, enabled: false, state: 'off' })).toBe('Off');
  expect(bridgeStatus({ ...b, state: 'starting' })).toBe('Starting...');
  expect(bridgeStatus(b)).toBe('On at 127.0.0.1:8787');
  expect(bridgeStatus({ ...b, state: 'failed', error: 'BindException' })).toBe(
    'Port 8787 is in use by another app. Choose another port.',
  );
  expect(bridgeStatus({ ...b, state: 'failed', error: 'SecurityException' })).toBe(
    'The bridge could not start (SecurityException).',
  );
});
