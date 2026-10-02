/// <reference types="node" />
// Node-only test. The Kotlin share path (Intake.kt) has no JS runtime, so it is a twin of
// classifyShare and splitSharedText; both are pinned to the same vectors.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { classifyShare } from '../src/intake/classify';
import { splitSharedText } from '../src/intake/paragraphs';

type Vectors = {
  classify: { in: string; out: { kind: string; url?: string } }[];
  split: { in: string; out: string[] }[];
};
const vectors: Vectors = JSON.parse(
  readFileSync(join(__dirname, 'fixtures/intake-vectors.json'), 'utf8'),
);

test.each(vectors.classify.map(v => [JSON.stringify(v.in), v] as const))(
  'classify %s',
  (_, v) => {
    const got = classifyShare(v.in);
    expect(got.kind).toBe(v.out.kind);
    if (v.out.kind === 'link' && got.kind === 'link')
      expect(got.url).toBe(v.out.url);
  },
);

test.each(vectors.split.map(v => [JSON.stringify(v.in), v] as const))(
  'split %s',
  (_, v) => {
    expect(splitSharedText(v.in).map(p => p.text)).toEqual(v.out);
  },
);
