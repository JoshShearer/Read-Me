// R-M08: paragraphs to sentences with exact character offsets. Intl.Segmenter when the
// runtime has it; Hermes does not (SPIKE-03), so the fallback below is the device path and
// Task 6's devcheck proves Hermes and Node agree on it.
import type { Paragraph, Sentence } from '../types';

export type SegmenterMode = 'auto' | 'intl' | 'fallback';
export type SegmentOptions = { mode?: SegmenterMode; locale?: string };

export const MAX_SENTENCE = 400;

type Span = { start: number; end: number };
type IntlSegmenterCtor = new (
  locale: string,
  options: { granularity: 'sentence' },
) => { segment(text: string): Iterable<{ segment: string; index: number }> };

function intlSegmenter(): IntlSegmenterCtor | undefined {
  const ctor = (Intl as unknown as { Segmenter?: unknown }).Segmenter;
  return typeof ctor === 'function' ? (ctor as IntlSegmenterCtor) : undefined;
}

export function hasIntlSegmenter(): boolean {
  return intlSegmenter() !== undefined;
}

const isSpace = (c: string) => /\s/.test(c);

function pushTrimmed(
  text: string,
  start: number,
  end: number,
  out: Span[],
): void {
  while (start < end && isSpace(text[start])) start++;
  while (end > start && isSpace(text[end - 1])) end--;
  if (end > start) out.push({ start, end });
}

function intlSpans(
  text: string,
  locale: string,
  Ctor: IntlSegmenterCtor,
): Span[] {
  const spans: Span[] = [];
  for (const s of new Ctor(locale, { granularity: 'sentence' }).segment(text)) {
    pushTrimmed(text, s.index, s.index + s.segment.length, spans);
  }
  return spans;
}

const TERMINAL = '.!?…';
const CJK_TERMINAL = '。！？';
const CLOSERS = '"\')]}»”’」』';
const NO_SPLIT_BEFORE = ',;:)]}';
// Always an abbreviation before a following word.
const ABBREVIATIONS = new Set([
  'mr',
  'mrs',
  'ms',
  'dr',
  'prof',
  'sr',
  'jr',
  'st',
  'vs',
  'etc',
  'approx',
  'dept',
  'inc',
  'ltd',
  'co',
  'corp',
  'mt',
  'gen',
  'gov',
  'sen',
  'rep',
  'jan',
  'feb',
  'mar',
  'apr',
  'jun',
  'jul',
  'aug',
  'sep',
  'sept',
  'oct',
  'nov',
  'dec',
]);
// Abbreviations only before a number ("No. 5", "fig. 3"); "I said no. Then" still splits.
const NUMBER_ABBREVIATIONS = new Set([
  'no',
  'fig',
  'vol',
  'ch',
  'p',
  'pp',
  'art',
  'sec',
]);

const isLower = (c: string) => c !== c.toUpperCase() && c === c.toLowerCase();

function isAbbreviation(text: string, dot: number, next: string): boolean {
  let s = dot;
  while (s > 0 && /[A-Za-z.]/.test(text[s - 1])) s--;
  const token = text.slice(s, dot);
  if (token === '') return false;
  if (/^[A-Z]$/.test(token)) return true;
  if (/^([A-Za-z]\.)+[A-Za-z]$/.test(token)) return true;
  const lower = token.toLowerCase();
  if (ABBREVIATIONS.has(lower)) return true;
  return NUMBER_ABBREVIATIONS.has(lower) && /[0-9]/.test(next);
}

function fallbackSpans(text: string): Span[] {
  const spans: Span[] = [];
  let start = 0;
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    const cjk = CJK_TERMINAL.includes(c);
    if (!cjk && !TERMINAL.includes(c)) {
      i++;
      continue;
    }
    let j = i + 1;
    while (
      j < text.length &&
      (TERMINAL.includes(text[j]) ||
        CJK_TERMINAL.includes(text[j]) ||
        CLOSERS.includes(text[j]))
    ) {
      j++;
    }
    let k = j;
    while (k < text.length && isSpace(text[k])) k++;
    if (k >= text.length) break;
    const next = text[k];
    const boundary = cjk
      ? true
      : k > j &&
        !isLower(next) &&
        !NO_SPLIT_BEFORE.includes(next) &&
        !(c === '.' && isAbbreviation(text, i, next));
    if (boundary) {
      pushTrimmed(text, start, j, spans);
      start = k;
      i = k;
    } else {
      i = j;
    }
  }
  pushTrimmed(text, start, text.length, spans);
  return spans;
}

function lastClauseCut(text: string, start: number, windowEnd: number): number {
  const half = start + MAX_SENTENCE / 2;
  for (let p = windowEnd - 1; p >= half; p--) {
    if (
      ',;:'.includes(text[p]) &&
      p + 1 < text.length &&
      isSpace(text[p + 1])
    ) {
      return p + 1;
    }
  }
  return -1;
}

function lastSpaceCut(text: string, start: number, windowEnd: number): number {
  for (let p = Math.min(windowEnd, text.length - 1); p > start; p--) {
    if (isSpace(text[p])) return p;
  }
  return -1;
}

function capSpan(text: string, span: Span, out: Span[]): void {
  let start = span.start;
  const end = span.end;
  while (end - start > MAX_SENTENCE) {
    const windowEnd = start + MAX_SENTENCE;
    let cut = lastClauseCut(text, start, windowEnd);
    if (cut <= start) cut = lastSpaceCut(text, start, windowEnd);
    if (cut <= start) {
      cut = windowEnd;
      const unit = text.charCodeAt(cut - 1);
      if (unit >= 0xd800 && unit <= 0xdbff) cut--;
    }
    pushTrimmed(text, start, cut, out);
    start = cut;
    while (start < end && isSpace(text[start])) start++;
  }
  pushTrimmed(text, start, end, out);
}

export function segmentParagraph(
  text: string,
  paragraphIndex: number,
  options: SegmentOptions = {},
): Sentence[] {
  const mode = options.mode ?? 'auto';
  const Ctor = mode === 'fallback' ? undefined : intlSegmenter();
  if (mode === 'intl' && Ctor === undefined) {
    throw new Error('Intl.Segmenter is not available');
  }
  const spans = Ctor
    ? intlSpans(text, options.locale ?? 'en', Ctor)
    : fallbackSpans(text);
  const capped: Span[] = [];
  for (const s of spans) capSpan(text, s, capped);
  return capped.map(s => ({
    paragraphIndex,
    start: s.start,
    end: s.end,
    text: text.slice(s.start, s.end),
  }));
}

export function segment(
  paragraphs: readonly Paragraph[],
  cuts: ReadonlySet<number> = new Set(),
  options: SegmentOptions = {},
): Sentence[] {
  const out: Sentence[] = [];
  paragraphs.forEach((p, i) => {
    if (cuts.has(i)) return;
    for (const s of segmentParagraph(p.text, i, options)) out.push(s);
  });
  return out;
}
