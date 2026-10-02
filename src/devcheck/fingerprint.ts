// A deterministic summary of one pipeline run, computed the same way on Hermes (the devcheck
// bundle) and in Node (Jest), so a hash mismatch means the runtimes disagree (roadmap F19).
// Fixture text only; never used on items.
import { extractArticle, type ExtractTimings } from '../extract/extract';
import { splitSharedText } from '../intake/paragraphs';
import { segment, type SegmenterMode } from '../segment/segment';
import type { Paragraph, Sentence } from '../types';

export type Fingerprint = {
  name: string;
  bytes?: number;
  extractMs: number;
  stages?: ExtractTimings;
  segmentMs: number;
  paragraphs: number;
  chars: number;
  sentences: number;
  poor: boolean;
  hash: string;
};

// FNV-1a needs 32-bit unsigned arithmetic; the bitwise operators are the point here.
/* eslint-disable no-bitwise */
export function fnv1a(s: string, seed = 0x811c9dc5): number {
  let h = seed >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h;
}
/* eslint-enable no-bitwise */

function summarize(
  name: string,
  title: string,
  paragraphs: Paragraph[],
  sentences: Sentence[],
): Pick<Fingerprint, 'name' | 'paragraphs' | 'chars' | 'sentences' | 'hash'> {
  let h = fnv1a(title);
  for (const p of paragraphs) h = fnv1a(`${p.kind}\u0000${p.text}\u0000`, h);
  for (const s of sentences)
    h = fnv1a(`${s.paragraphIndex}:${s.start}:${s.end};`, h);
  return {
    name,
    paragraphs: paragraphs.length,
    chars: paragraphs.reduce((n, p) => n + p.text.length, 0),
    sentences: sentences.length,
    hash: h.toString(16),
  };
}

export function fingerprintPage(
  name: string,
  html: string,
  bytes: number,
  mode: SegmenterMode,
): Fingerprint {
  const stages: ExtractTimings = {};
  const t0 = Date.now();
  const ex = extractArticle(html, undefined, stages);
  const t1 = Date.now();
  const sentences = segment(ex.paragraphs, new Set(), { mode });
  const t2 = Date.now();
  return {
    ...summarize(name, ex.title, ex.paragraphs, sentences),
    bytes,
    extractMs: t1 - t0,
    stages,
    segmentMs: t2 - t1,
    poor: ex.poor,
  };
}

export function fingerprintText(
  name: string,
  text: string,
  mode: SegmenterMode,
): Fingerprint {
  const t0 = Date.now();
  const paragraphs = splitSharedText(text);
  const t1 = Date.now();
  const sentences = segment(paragraphs, new Set(), { mode });
  const t2 = Date.now();
  return {
    ...summarize(name, '', paragraphs, sentences),
    extractMs: t1 - t0,
    segmentMs: t2 - t1,
    poor: false,
  };
}
