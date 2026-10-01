// SPIKE-02: does Readability run on Hermes over a pure-JS DOM (linkedom), and how fast
// for pages up to R-M03's 5 MB cap? Reports sizes and timings, never extracted text.
import {Readability} from '@mozilla/readability';
import {parseHTML} from 'linkedom';
import {FIXTURES} from './fixtures.generated';

export type ExtractResult = {
  name: string;
  bytes: number;
  parseMs: number;
  readabilityMs: number;
  blocksMs: number;
  titleChars: number;
  textChars: number;
  blocks: number;
};

type ReadabilityDoc = ConstructorParameters<typeof Readability>[0];

// UTF-8 byte length without Buffer or TextEncoder, which Hermes may not provide.
function utf8Bytes(s: string): number {
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (c >= 0xd800 && c <= 0xdbff) {
      n += 4;
      i++;
    } else n += 3;
  }
  return n;
}

export async function extractProbe(): Promise<{results: ExtractResult[]}> {
  const results: ExtractResult[] = [];
  for (const f of FIXTURES) {
    const t0 = Date.now();
    const {document} = parseHTML(f.html);
    const t1 = Date.now();
    const article = new Readability(document as unknown as ReadabilityDoc).parse();
    const t2 = Date.now();
    const blocks = article?.content
      ? parseHTML(article.content).document.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li').length
      : 0;
    const t3 = Date.now();
    results.push({
      name: f.name,
      bytes: utf8Bytes(f.html),
      parseMs: t1 - t0,
      readabilityMs: t2 - t1,
      blocksMs: t3 - t2,
      titleChars: article?.title?.length ?? 0,
      textChars: article?.textContent?.length ?? 0,
      blocks,
    });
  }
  return {results};
}
