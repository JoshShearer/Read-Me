import type {
  NativePlayback,
  NativeSentence,
} from '../src/native/NativeReadMeSpeech';

const mockPlays: { id: number; title: string; sentences: NativeSentence[]; start: number }[] = [];
const mockState = { resume: true, position: null as null | { paragraphIndex: number; charOffset: number } };

jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    getItem: jest.fn(async (id: number) =>
      id === 404
        ? null
        : {
            item: {
              id, kind: 'text', url: null, title: 'T', site: null, byline: null, createdAt: 1,
              state: 'ready', failReason: null, openedAt: null, archivedAt: null,
            },
            paragraphs: [
              { kind: 'p', text: 'One here. Two here.' },
              { kind: 'p', text: 'Three here.' },
            ],
            cuts: id === 2 ? [0, 1] : [],
          },
    ),
    getPosition: jest.fn(async () => mockState.position),
    play: jest.fn(async (id: number, title: string, sentences: NativeSentence[], start: number) => {
      mockPlays.push({ id, title, sentences, start });
      return true;
    }),
    pause: jest.fn(async () => true),
    setCuts: jest.fn(async () => undefined),
    stop: jest.fn(async () => true),
    resume: jest.fn(async () => mockState.resume),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

import Native from '../src/native/NativeReadMeSpeech';
import { applyCuts, engineBlocked, engineProblem, marker, plan, playItem, toggle, type Playback } from '../src/library/playback';
import type { Item } from '../src/library/library';

const paragraphs = [
  { kind: 'p' as const, text: 'One here. Two here.' },
  { kind: 'p' as const, text: 'Cut away.' },
  { kind: 'p' as const, text: 'Three here.' },
];

beforeEach(() => {
  mockPlays.length = 0;
  mockState.resume = true;
  mockState.position = null;
  jest.clearAllMocks();
});

describe('plan', () => {
  test('no saved position starts at the first kept sentence', () => {
    const p = plan(paragraphs, [1], null)!;
    expect(p.sentences.map(s => s.text)).toEqual(['One here.', 'Two here.', 'Three here.']);
    expect(p.startIndex).toBe(0);
  });

  test('a saved offset resumes at the start of the sentence containing it', () => {
    expect(plan(paragraphs, [1], { paragraphIndex: 0, charOffset: 13 })!.startIndex).toBe(1);
  });

  test('a position in a paragraph cut since moves to the next kept paragraph', () => {
    expect(plan(paragraphs, [1], { paragraphIndex: 1, charOffset: 4 })!.startIndex).toBe(2);
  });

  test('a position past the end starts over', () => {
    expect(plan(paragraphs, [1, 2], { paragraphIndex: 2, charOffset: 0 })!.startIndex).toBe(0);
  });

  test('everything cut gives nothing to play', () => {
    expect(plan(paragraphs, [0, 1, 2], null)).toBeNull();
  });
});

describe('playItem', () => {
  test('hands the native side the kept sentences and the start', async () => {
    mockState.position = { paragraphIndex: 1, charOffset: 0 };
    expect(await playItem(1)).toBe(true);
    expect(mockPlays).toHaveLength(1);
    expect(mockPlays[0].title).toBe('T');
    expect(mockPlays[0].sentences).toHaveLength(3);
    expect(mockPlays[0].start).toBe(2);
  });

  test('a missing item or an item trimmed to nothing does not play', async () => {
    expect(await playItem(404)).toBe(false);
    expect(await playItem(2)).toBe(false);
    expect(mockPlays).toHaveLength(0);
  });
});

describe('toggle', () => {
  const at = (itemId: number | null, playing: boolean): Playback => ({
    itemId, playing, sentence: null, rate: 2, engine: 'ready',
  });

  test('pauses the item that is playing', async () => {
    await toggle(1, at(1, true));
    expect(Native.pause).toHaveBeenCalled();
    expect(mockPlays).toHaveLength(0);
  });

  test('resumes the paused item', async () => {
    await toggle(1, at(1, false));
    expect(Native.resume).toHaveBeenCalled();
    expect(mockPlays).toHaveLength(0);
  });

  test('plays again from the saved position when the service is gone', async () => {
    mockState.resume = false;
    await toggle(1, at(1, false));
    expect(mockPlays).toHaveLength(1);
  });

  test('plays another item', async () => {
    await toggle(3, at(1, true));
    expect(mockPlays[0].id).toBe(3);
  });
});

test('markers and the engine problem', () => {
  const item = { id: 1, archivedAt: undefined } as Item;
  const playing: Playback = { itemId: 1, playing: true, sentence: null, rate: 2, engine: 'ready' };
  expect(marker(item, playing)).toBe('playing');
  expect(marker(item, { ...playing, playing: false })).toBe('paused');
  expect(marker({ ...item, archivedAt: 5 }, null)).toBe('archived');
  expect(marker(item, null)).toBe('');
  expect(engineProblem({ ...playing, engine: 'no-voice' })).toBe(true);
  expect(engineProblem({ ...playing, engine: 'no-engine' })).toBe(true);
  expect(engineProblem(playing)).toBe(false);
  expect(engineProblem(null)).toBe(false);
});

// Keeps the NativePlayback import used: the facade's event shape is the spec's.
export type _Shape = NativePlayback;

describe('applyCuts', () => {
  const at = (itemId: number | null, playing: boolean): Playback => ({
    itemId, playing, sentence: null, rate: 2, engine: 'ready',
  });

  test('a cut change while playing re-plays; while paused stops', async () => {
    // Review Focus 1: the service must never go on reading paragraphs that are now cut.
    await applyCuts(1, new Set([1, 0]), at(1, true));
    expect(Native.setCuts).toHaveBeenCalledWith(1, [0, 1]);
    expect(mockPlays).toHaveLength(1);
    await applyCuts(1, new Set([0]), at(1, false));
    expect(Native.stop).toHaveBeenCalledTimes(1);
    expect(mockPlays).toHaveLength(1);
  });

  test('a cut change on another item leaves playback alone', async () => {
    await applyCuts(2, new Set([0]), at(1, true));
    expect(Native.stop).not.toHaveBeenCalled();
    expect(mockPlays).toHaveLength(0);
  });
});

test('the engine blocks only when there is no engine or no offline voice', () => {
  expect(engineBlocked('no-voice')).toBe(true);
  expect(engineBlocked('no-engine')).toBe(true);
  expect(engineBlocked('ready')).toBe(false);
  expect(engineBlocked('unknown')).toBe(false);
  expect(engineBlocked(undefined)).toBe(false);
});
