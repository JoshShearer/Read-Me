// Writes src/devcheck/fixtures.generated.ts (gitignored) from the committed fixtures, plus a
// synthetic page of real prose at R-M03's 5 MB cap (the Gutenberg body repeated inside one
// <article>, cut on a tag boundary), the size SPIKE-02's F17 line names, and a deeply nested page.
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const CAP_BYTES = 5 * 1024 * 1024;
const pagesDir = '__tests__/fixtures/pages';
const textsDir = '__tests__/fixtures/text';

const pages = readdirSync(pagesDir)
  .filter(f => f.endsWith('.html'))
  .sort()
  .map(f => ({
    name: f.replace(/\.html$/, ''),
    html: readFileSync(join(pagesDir, f), 'utf8'),
  }));

const book = pages.find(p => p.name === 'gutenberg-1342');
if (!book)
  throw new Error(
    'missing gutenberg-1342.html; run scripts/fetch-page-fixtures.sh',
  );
const body = book.html.slice(
  book.html.indexOf('<body'),
  book.html.lastIndexOf('</body>'),
);
const inner = body.slice(body.indexOf('>') + 1);
const head =
  '<!doctype html><html><head><title>Synthetic 5 MB</title></head><body><article>';
const tail = '</article></body></html>';
const room = CAP_BYTES - Buffer.byteLength(head + tail);
let big = '';
while (Buffer.byteLength(big) < room) big += inner;
big = Buffer.from(big).subarray(0, room).toString('utf8');
big = big.slice(0, big.lastIndexOf('<'));
pages.push({ name: 'synthetic-5mb', html: head + big + tail });
// Final review: deep nesting overflowed a recursive walk and stalls Readability. 6000 levels of
// <div> around real prose checks, on Hermes's smaller stack, that it extracts fast and as poor.
const DEEP = 6000;
pages.push({
  name: 'synthetic-deep-6000',
  html:
    '<!doctype html><html><head><title>Synthetic deep</title></head><body>' +
    '<div>'.repeat(DEEP) +
    inner.slice(0, inner.indexOf('<', 20000)) +
    '</div>'.repeat(DEEP) +
    '</body></html>',
});

// Critique run A: Readability's time grows with depth times content, so the depth cut-off is
// set from real prose (300 KB of the Gutenberg body) at moderate depths, measured on Hermes.
const slice = inner.slice(0, inner.indexOf('<', 300 * 1024));
for (const depth of [60, 120]) {
  pages.push({
    name: `synthetic-300k-depth-${depth}`,
    html:
      '<!doctype html><html><head><title>Synthetic depth</title></head><body>' +
      '<div>'.repeat(depth) +
      slice +
      '</div>'.repeat(depth) +
      '</body></html>',
  });
}

// Critique round 3: element count drives Readability's time too, and the model's large-page
// side was never measured above 19 M units. These check the fitted model (ADR 0006) on the
// device: a comment thread, a long link index, and the 5 MB page wrapped four levels deeper.
const sentences = inner
  .replace(/<[^>]+>/g, ' ')
  .split(/(?<=[.!?])\s+/)
  .filter(x => x.length > 40)
  .slice(0, 4000);
let thread = '';
for (let i = 0; i < 2000; i++) {
  thread +=
    `<div class="comment"><div class="meta"><a href="/u/${i}">user${i}</a> <span>${i}h</span></div>` +
    `<div class="body"><p>${sentences[i % sentences.length]}</p></div></div>`;
}
pages.push({
  name: 'synthetic-comment-thread',
  html: `<!doctype html><html><head><title>Thread</title></head><body><main>${thread}</main></body></html>`,
});
let links = '';
for (let i = 0; links.length < 940 * 1024; i++) {
  links += `<li><a href="/page/${i}">${sentences[i % sentences.length].slice(
    0,
    60,
  )}</a></li>`;
}
pages.push({
  name: 'synthetic-link-index',
  html: `<!doctype html><html><head><title>Index</title></head><body><ul>${links}</ul></body></html>`,
});
pages.push({
  name: 'synthetic-5mb-wrapped',
  html:
    head.replace('<article>', '<div><div><div><div><article>') +
    big +
    tail.replace('</article>', '</article></div></div></div></div>'),
});

// Pages that may not be committed (Wikipedia and MDN are CC BY-SA) can still be measured:
// put them in the gitignored .claude/scratch/devcheck/local-pages/. They run as local-<name>
// and never leave this machine.
const localDir = '.claude/scratch/devcheck/local-pages';
if (existsSync(localDir)) {
  for (const f of readdirSync(localDir)
    .filter(x => x.endsWith('.html'))
    .sort()) {
    pages.push({
      name: `local-${f.replace(/\.html$/, '')}`,
      html: readFileSync(join(localDir, f), 'utf8'),
    });
  }
}

// DEVCHECK_ONLY=name,name runs just those fixtures, each in a fresh process with nothing
// heavy before it, to separate a page's own cost from heap and run-order effects.
const only = (process.env.DEVCHECK_ONLY ?? '').split(',').filter(Boolean);
const keep = n => only.length === 0 || only.includes(n);
for (const n of only) {
  if (
    ![
      ...pages.map(p => p.name),
      ...readdirSync(textsDir).map(f => f.replace(/\.txt$/, '')),
    ].includes(n)
  )
    throw new Error(`DEVCHECK_ONLY names an unknown fixture: ${n}`);
}
pages.splice(0, pages.length, ...pages.filter(p => keep(p.name)));

const texts = readdirSync(textsDir)
  .filter(f => f.endsWith('.txt'))
  .sort()
  .map(f => ({
    name: f.replace(/\.txt$/, ''),
    text: readFileSync(join(textsDir, f), 'utf8'),
  }))
  .filter(t => keep(t.name));

const withBytes = pages.map(p => ({
  name: p.name,
  bytes: Buffer.byteLength(p.html),
  html: p.html,
}));
writeFileSync(
  'src/devcheck/fixtures.generated.ts',
  '// Generated by scripts/make-devcheck-fixtures.mjs. Do not edit; gitignored.\n' +
    'export const PAGES: { name: string; bytes: number; html: string }[] = ' +
    JSON.stringify(withBytes) +
    ';\nexport const TEXTS: { name: string; text: string }[] = ' +
    JSON.stringify(texts) +
    ';\n',
);
for (const p of withBytes) console.log(p.name, p.bytes, 'bytes');
for (const t of texts) console.log(t.name, t.text.length, 'chars');
