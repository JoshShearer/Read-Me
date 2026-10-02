import type {
  NativeItem,
  NativeParagraph,
} from '../src/native/NativeReadMeSpeech';

// jest.mock factories may only reference variables whose names start with "mock".
type Row = NativeItem & { body?: string | null };
const mockRows: Row[] = [];
const mockCompleted: {
  id: number;
  title: string;
  paragraphs: NativeParagraph[];
  poor: boolean;
}[] = [];

jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    listItems: jest.fn(async () => mockRows.map(({ body: _b, ...r }) => ({ ...r }))),
    getBody: jest.fn(async (id: number) => mockRows.find(r => r.id === id)?.body ?? null),
    completeExtraction: jest.fn(
      async (
        id: number,
        title: string,
        _site: string | null,
        _byline: string | null,
        paragraphs: NativeParagraph[],
        poor: boolean,
      ) => {
        mockCompleted.push({ id, title, paragraphs, poor });
        const r = mockRows.find(x => x.id === id);
        if (r) r.state = poor ? 'extract-poor' : 'ready';
        return true;
      },
    ),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

import { drainFetched, listItems } from '../src/library/library';

const row = (id: number, state: string, body: string | null = null): Row => ({
  id,
  kind: 'link',
  url: `https://example.com/${id}`,
  title: 'example.com',
  site: null,
  byline: null,
  createdAt: id,
  state,
  failReason: null,
  openedAt: null,
  archivedAt: null,
  words: 0,
  progress: 0,
  body,
});

const fakeExtract = (html: string) => ({
  title: `T:${html}`,
  paragraphs: [{ kind: 'p' as const, text: html }],
  poor: html === 'thin',
});

beforeEach(() => {
  mockRows.length = 0;
  mockCompleted.length = 0;
});

test('listItems maps native nulls to absent fields', async () => {
  mockRows.push(row(1, 'ready'));
  const [item] = await listItems();
  expect(item.site).toBeUndefined();
  expect(item.failReason).toBeUndefined();
  expect(item.url).toBe('https://example.com/1');
});

test('drain extracts only fetched items and records poor ones', async () => {
  mockRows.push(row(1, 'fetched', 'page'), row(2, 'fetching'), row(3, 'fetched', 'thin'));
  expect(await drainFetched(fakeExtract)).toBe(2);
  expect(mockCompleted.map(c => [c.id, c.poor])).toEqual([
    [1, false],
    [3, true],
  ]);
  expect(mockCompleted[0].title).toBe('T:page');
});

test('a fetched item whose body is gone is skipped (the native side fails it)', async () => {
  mockRows.push(row(1, 'fetched', null));
  expect(await drainFetched(fakeExtract)).toBe(0);
});

test('an extractor that throws still completes the item as poor', async () => {
  mockRows.push(row(1, 'fetched', 'page'));
  const boom = () => {
    throw new Error('x');
  };
  expect(await drainFetched(boom)).toBe(1);
  expect(mockCompleted[0].poor).toBe(true);
  expect(mockCompleted[0].paragraphs).toEqual([]);
});

test('a second drain request during a drain runs again (Review Focus 2)', async () => {
  mockRows.push(row(1, 'fetched', 'page'));
  const first = drainFetched(fakeExtract);
  mockRows.push(row(2, 'fetched', 'late')); // completes while the first drain runs
  const second = drainFetched(fakeExtract);
  expect(await first).toBe(2);
  expect(await second).toBe(2);
  expect(mockCompleted.map(c => c.id).sort()).toEqual([1, 2]);
});

test('one item whose native calls fail does not stop the rest (final review 5)', async () => {
  const Native = jest.requireMock('../src/native/NativeReadMeSpeech').default;
  mockRows.push(row(1, 'fetched', 'a'), row(2, 'fetched', 'b'));
  Native.getBody.mockImplementationOnce(async () => {
    throw new Error('FileNotFoundException');
  });
  expect(await drainFetched(fakeExtract)).toBe(1);
  expect(mockCompleted.map(c => c.id)).toEqual([2]);
});

test('items carry their word count and progress', async () => {
  mockRows.length = 0;
  mockRows.push({ ...row(1, 'ready'), words: 120, progress: 0.25 });
  const [item] = await listItems();
  expect(item.words).toBe(120);
  expect(item.progress).toBe(0.25);
});
