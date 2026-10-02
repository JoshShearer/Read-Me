/// <reference types="node" />
// Node-only test: the app source never sees these types.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { extractArticle } from '../src/extract/extract';

const page = (name: string) =>
  readFileSync(join(__dirname, 'fixtures/pages', name), 'utf8');

// Long enough for Readability to accept as an article (its default charThreshold is 500).
const PROSE =
  'The river rose for three days and the town watched it climb the stone wall. ' +
  'Nobody had seen water that high since the old mill burned, and the ferry stopped. ';

const STRUCTURED = `<!doctype html><html><head><title>River Notes</title></head><body>
<nav><a href="/">Home</a> NAVLINK</nav>
<article>
  <h1>River Notes</h1>
  <p>${PROSE}</p>
  <h2>Second section</h2>
  <p>${PROSE} Inline code like <code>npm test</code> stays.</p>
  <ul><li>First item<ul><li>Inner item</li></ul></li><li>Second item</li></ul>
  <table><tr><th>TABLEHEAD</th></tr><tr><td>TABLECELL</td></tr></table>
  <figure><img src="a.png" alt="ALTTEXT"><figcaption>CAPTIONTEXT</figcaption></figure>
  <pre><code>CODEBLOCK();</code></pre>
  <iframe src="https://example.com/embed">IFRAMETEXT</iframe>
  <p>${PROSE}</p>
</article></body></html>`;

describe('extractArticle structure (R-M04)', () => {
  const ex = extractArticle(STRUCTURED, 'https://www.example.com/notes');
  const texts = ex.paragraphs.map(p => p.text);
  const all = texts.join('\n');

  test('title, and the site falls back to the host without www', () => {
    expect(ex.title).toBe('River Notes');
    expect(ex.site).toBe('example.com');
  });

  test('headings and list items are their own paragraphs, in order', () => {
    const heading = ex.paragraphs.findIndex(
      p => p.kind === 'heading' && p.text === 'Second section',
    );
    const first = ex.paragraphs.findIndex(
      p => p.kind === 'li' && p.text === 'First item',
    );
    expect(heading).toBeGreaterThanOrEqual(0);
    expect(first).toBeGreaterThan(heading);
    expect(ex.paragraphs).toContainEqual({ kind: 'li', text: 'Inner item' });
    expect(ex.paragraphs).toContainEqual({ kind: 'li', text: 'Second item' });
  });

  test('inline code is kept as text', () => {
    expect(all).toContain('Inline code like npm test stays.');
  });

  test('tables, figures, captions, code blocks, embeds and nav are dropped', () => {
    for (const gone of [
      'TABLEHEAD',
      'TABLECELL',
      'ALTTEXT',
      'CAPTIONTEXT',
      'CODEBLOCK',
      'IFRAMETEXT',
      'NAVLINK',
    ]) {
      expect(all).not.toContain(gone);
    }
  });

  test("an article's own header (lead heading, standfirst) is kept", () => {
    // R-M04 drops only tables, figures, code blocks, captions and embedded media from the
    // article. Readability keeps an <article>'s <header>; it is content, not page chrome.
    const html = `<!doctype html><html><head><title>River Notes</title></head><body>
<article><header><h2>Lead Heading</h2><p>A standfirst that sums the piece up.</p></header>
<p>${PROSE}</p><h2>Second heading</h2><p>${PROSE}</p><p>${PROSE}</p></article>
</body></html>`;
    const kept = extractArticle(html).paragraphs.map(p => p.text);
    expect(kept).toContain('Lead Heading');
    expect(kept).toContain('A standfirst that sums the piece up.');
  });

  test('paragraph text is whitespace-collapsed and never empty', () => {
    for (const t of texts) {
      expect(t).toBe(t.replace(/\s+/g, ' ').trim());
      expect(t.length).toBeGreaterThan(0);
    }
  });

  test('a substantial article is not poor', () => {
    expect(ex.poor).toBe(false);
  });
});

describe('pages with no article never throw (Review Focus 4)', () => {
  test('a JavaScript-rendered shell is poor', () => {
    const ex = extractArticle(
      '<!doctype html><html><head><title>App</title></head><body><div id="root"></div>' +
        '<script>render()</script></body></html>',
    );
    expect(ex.poor).toBe(true);
    expect(ex.title).toBe('App');
  });

  test('a paywall stub is poor but keeps what it found', () => {
    const ex = extractArticle(
      '<html><head><title>Story</title></head><body><article><p>The first line of the ' +
        'story.</p><p>Subscribe to read more.</p></article></body></html>',
    );
    expect(ex.poor).toBe(true);
    expect(ex.paragraphs.map(p => p.text).join(' ')).toContain('first line');
  });

  test('deeply nested markup gives its text, quickly, as poor, and never throws', () => {
    // Readability's time grows steeply with depth (Node: 0.7 s at 500 levels, 63 s at 2000),
    // and a recursive walk overflowed the stack near 6000. Hermes is slower with a smaller
    // stack. The text inside must still come out, without a stall.
    for (const tag of ['div', 'span']) {
      const n = 6000;
      const html = `<html><body>${`<${tag}>`.repeat(n)}${PROSE}${`</${tag}>`.repeat(n)}</body></html>`;
      let ex: ReturnType<typeof extractArticle> | undefined;
      expect(() => {
        ex = extractArticle(html);
      }).not.toThrow();
      expect(ex?.poor).toBe(true);
      expect(ex?.paragraphs.some(p => p.text.includes('stone wall'))).toBe(true);
    }
  }, 20000);

  test('malformed HTML and an empty string do not throw', () => {
    expect(() =>
      extractArticle('<p>Unclosed <b>bold <i>both</p><div>'),
    ).not.toThrow();
    const empty = extractArticle('');
    expect(empty.poor).toBe(true);
    expect(empty.title).toBe('');
    expect(empty.paragraphs).toEqual([]);
  });
});

describe('saved real pages (R-M14)', () => {
  test.each([
    ['weather-gov-lightning.html', /lightning/i, 5],
    ['weather-gov-flood.html', /flood/i, 5],
    ['gutenberg-1342.html', /Elizabeth/, 1000],
  ])('%s extracts as a readable article', (file, word, minParagraphs) => {
    const ex = extractArticle(page(file));
    expect(ex.poor).toBe(false);
    expect(ex.title.length).toBeGreaterThan(0);
    expect(ex.paragraphs.length).toBeGreaterThanOrEqual(minParagraphs);
    expect(ex.paragraphs.some(p => word.test(p.text))).toBe(true);
    for (const p of ex.paragraphs) {
      expect(['p', 'heading', 'li']).toContain(p.kind);
      expect(p.text.length).toBeGreaterThan(0);
    }
  });
});
