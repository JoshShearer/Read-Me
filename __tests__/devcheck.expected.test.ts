/// <reference types="node" />
// Node-only test: the app source never sees these types.
import { writeFileSync } from 'node:fs';
import {
  fingerprintPage,
  fingerprintText,
  fnv1a,
} from '../src/devcheck/fingerprint';

test('fnv1a is the 32-bit FNV-1a of UTF-16 code units', () => {
  expect(fnv1a('')).toBe(0x811c9dc5);
  expect(fnv1a('a')).toBe(0xe40c292c);
});

test('a fingerprint is deterministic and covers structure', () => {
  const html =
    '<html><head><title>T</title></head><body><article><p>One. Two.</p></article></body></html>';
  const a = fingerprintPage('x', html, html.length, 'fallback');
  const b = fingerprintPage('x', html, html.length, 'fallback');
  expect(a.hash).toBe(b.hash);
  expect(a.sentences).toBe(2);
  const t = fingerprintText('t', 'A b.\n\nC d.', 'fallback');
  expect(t.paragraphs).toBe(2);
  expect(t.sentences).toBe(2);
});

// Writes Node's expected fingerprints for scripts/devcheck.sh. Skipped in normal runs: the
// 5 MB page makes it slow, and the generated module is only loaded when it runs.
const out = process.env.DEVCHECK_EXPECTED_OUT;
(out ? test : test.skip)(
  'write Node expectations for the on-device devcheck',
  () => {
    const { PAGES, TEXTS } = require('../src/devcheck/fixtures.generated');
    const all = [
      ...PAGES.map((p: { name: string; html: string; bytes: number }) =>
        fingerprintPage(p.name, p.html, p.bytes, 'fallback'),
      ),
      ...TEXTS.map((t: { name: string; text: string }) =>
        fingerprintText(t.name, t.text, 'fallback'),
      ),
    ];
    writeFileSync(
      out as string,
      JSON.stringify(
        all.map(({ name, bytes, hash, paragraphs, sentences }) => ({
          name,
          bytes,
          hash,
          paragraphs,
          sentences,
        })),
      ),
    );
  },
  300_000,
);
