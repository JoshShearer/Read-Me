import {
  MAX_SENTENCE,
  hasIntlSegmenter,
  segment,
  segmentParagraph,
} from '../src/segment/segment';
import { positionOf, sentenceIndexAt } from '../src/segment/locate';
import type { Paragraph } from '../src/types';

const fallback = (text: string) =>
  segmentParagraph(text, 0, { mode: 'fallback' }).map(s => s.text);

const SAMPLE =
  'Dr. Smith went to Washington. He arrived at 3 p.m. on Jan. 5, 2026! ' +
  'Did it work? "Yes," she said. The U.S. economy grew 2.5% last year.';

describe('fallback segmenter (the Hermes path, SPIKE-03)', () => {
  test('abbreviations, decimals, quotes and questions', () => {
    expect(fallback(SAMPLE)).toEqual([
      'Dr. Smith went to Washington.',
      'He arrived at 3 p.m. on Jan. 5, 2026!',
      'Did it work?',
      '"Yes," she said.',
      'The U.S. economy grew 2.5% last year.',
    ]);
  });

  test('a closing quote stays with its sentence', () => {
    expect(fallback('He said "Stop." Then left.')).toEqual([
      'He said "Stop."',
      'Then left.',
    ]);
  });

  test('initials and number abbreviations do not split', () => {
    expect(fallback('J. R. R. Tolkien wrote it. See fig. 3 now.')).toEqual([
      'J. R. R. Tolkien wrote it.',
      'See fig. 3 now.',
    ]);
  });

  test('a lowercase continuation does not split', () => {
    expect(fallback('Wait... what happened? Nothing.')).toEqual([
      'Wait... what happened?',
      'Nothing.',
    ]);
  });

  test('accented capitals and caseless scripts start a sentence', () => {
    expect(fallback('Il est là. Élise arrive.')).toEqual([
      'Il est là.',
      'Élise arrive.',
    ]);
    expect(fallback('שלום לך. מה שלומך?')).toEqual(['שלום לך.', 'מה שלומך?']);
  });

  test('CJK full stops split with no following space', () => {
    expect(fallback('今日は晴れです。明日は雨です。')).toEqual([
      '今日は晴れです。',
      '明日は雨です。',
    ]);
  });

  test('every sentence is the exact slice its offsets name', () => {
    for (const s of segmentParagraph(SAMPLE, 4, { mode: 'fallback' })) {
      expect(s.paragraphIndex).toBe(4);
      expect(SAMPLE.slice(s.start, s.end)).toBe(s.text);
      expect(s.text).toBe(s.text.trim());
    }
  });
});

describe('400-character cap (R-M08)', () => {
  test('splits at whitespace, losing nothing', () => {
    const long = 'word '.repeat(120).trim() + '.';
    const out = fallback(long);
    expect(out.length).toBeGreaterThan(1);
    expect(out.every(t => t.length <= MAX_SENTENCE)).toBe(true);
    expect(out.join(' ')).toBe(long);
  });

  test('prefers a clause boundary in the second half of the window', () => {
    const long = 'a'.repeat(250) + ', ' + 'b '.repeat(150).trim() + '.';
    expect(fallback(long)[0]).toBe('a'.repeat(250) + ',');
  });

  test('hard-cuts text with no boundary at exactly 400', () => {
    expect(fallback('x'.repeat(900)).map(t => t.length)).toEqual([
      400, 400, 100,
    ]);
  });

  test('never cuts inside a surrogate pair', () => {
    const out = fallback('a' + '\u{1F600}'.repeat(300));
    for (const t of out) {
      expect(t.length).toBeLessThanOrEqual(MAX_SENTENCE);
      const last = t.charCodeAt(t.length - 1);
      expect(last >= 0xd800 && last <= 0xdbff).toBe(false);
    }
    expect(out.join('')).toBe('a' + '\u{1F600}'.repeat(300));
  });
});

describe('Intl path and mode selection', () => {
  test('Node has Intl.Segmenter, and the intl mode gives exact offsets', () => {
    expect(hasIntlSegmenter()).toBe(true);
    const out = segmentParagraph(SAMPLE, 0, { mode: 'intl' });
    expect(out.length).toBeGreaterThanOrEqual(4);
    for (const s of out) expect(SAMPLE.slice(s.start, s.end)).toBe(s.text);
  });

  test('without Intl.Segmenter, auto falls back and intl throws', () => {
    const intl = Intl as unknown as { Segmenter?: unknown };
    const saved = intl.Segmenter;
    delete intl.Segmenter;
    try {
      expect(hasIntlSegmenter()).toBe(false);
      expect(segmentParagraph(SAMPLE, 0, { mode: 'auto' })).toEqual(
        segmentParagraph(SAMPLE, 0, { mode: 'fallback' }),
      );
      expect(() => segmentParagraph(SAMPLE, 0, { mode: 'intl' })).toThrow(
        'Intl.Segmenter is not available',
      );
    } finally {
      intl.Segmenter = saved;
    }
  });
});

const PARAS: Paragraph[] = [
  { kind: 'heading', text: 'Title here.' },
  { kind: 'p', text: 'One. Two.' },
  { kind: 'p', text: 'Three. Four.' },
];

describe('segment over paragraphs with cuts', () => {
  test('cut paragraphs produce no sentences; indices are kept', () => {
    const out = segment(PARAS, new Set([1]), { mode: 'fallback' });
    expect(out.map(s => [s.paragraphIndex, s.text])).toEqual([
      [0, 'Title here.'],
      [2, 'Three.'],
      [2, 'Four.'],
    ]);
  });
});

describe('sentenceIndexAt and positionOf (R-M11)', () => {
  const sentences = segment(PARAS, new Set(), { mode: 'fallback' });
  const at = (paragraphIndex: number, charOffset: number) =>
    sentenceIndexAt(sentences, { paragraphIndex, charOffset });

  test('an offset inside a sentence resumes at that sentence', () => {
    expect(sentences[at(1, 6)].text).toBe('Two.');
    expect(sentences[at(1, 0)].text).toBe('One.');
  });

  test('an offset on the space between sentences resumes at the next', () => {
    expect(sentences[at(1, 4)].text).toBe('Two.');
  });

  test('an offset past the end of a paragraph resumes in the next one', () => {
    expect(sentences[at(1, 999)].text).toBe('Three.');
  });

  test('a position in a cut paragraph resumes at the next kept one', () => {
    const kept = segment(PARAS, new Set([1]), { mode: 'fallback' });
    const i = sentenceIndexAt(kept, { paragraphIndex: 1, charOffset: 2 });
    expect(kept[i].text).toBe('Three.');
  });

  test('past the last sentence there is nothing to resume', () => {
    expect(at(2, 999)).toBe(-1);
    expect(sentenceIndexAt([], { paragraphIndex: 0, charOffset: 0 })).toBe(-1);
  });

  test('positionOf is the sentence start', () => {
    expect(positionOf(sentences[at(2, 8)])).toEqual({
      paragraphIndex: 2,
      charOffset: 7,
    });
  });
});
