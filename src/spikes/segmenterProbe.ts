// SPIKE-03: is Intl.Segmenter (sentence granularity) present on Hermes, how does it
// treat abbreviations, and how fast is it on ~150k characters?
type Segment = {segment: string; index: number};
type SegmenterCtor = new (
  locale: string,
  opts: {granularity: 'sentence'},
) => {segment(text: string): Iterable<Segment>};

// Fixed sample: abbreviations, a decimal, a quote and a question are where segmenters differ.
const SAMPLE =
  'Dr. Smith went to Washington. He arrived at 3 p.m. on Jan. 5, 2026! ' +
  'Did it work? "Yes," she said. The U.S. economy grew 2.5% last year.';

export async function segmenterProbe(): Promise<Record<string, unknown>> {
  const Seg = (Intl as unknown as {Segmenter?: SegmenterCtor}).Segmenter;
  const hermes = 'HermesInternal' in globalThis;
  if (typeof Seg !== 'function') {
    return {present: false, hermes};
  }
  const seg = new Seg('en', {granularity: 'sentence'});
  const sentences = Array.from(seg.segment(SAMPLE), s => ({index: s.index, text: s.segment}));
  const big = SAMPLE.repeat(1000);
  const t0 = Date.now();
  const bigSentences = Array.from(seg.segment(big)).length;
  return {present: true, hermes, sentences, bigChars: big.length, bigSentences, bigMs: Date.now() - t0};
}
