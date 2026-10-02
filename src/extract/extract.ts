// R-M04: HTML to title, site, byline and paragraphs, on-device: Readability over linkedom
// (SPIKE-02). Returns structure only; the caller discards the HTML. Never logs.
import { Readability } from '@mozilla/readability';
import { parseHTML } from 'linkedom';
import type { Paragraph, ParagraphKind } from '../types';

export type Extracted = {
  title: string;
  site?: string;
  byline?: string;
  paragraphs: Paragraph[];
  poor: boolean;
};

export const POOR_MIN_PARAGRAPHS = 3;
export const POOR_MIN_CHARS = 500;

// The TS lib here has no DOM types; this is the part of linkedom's node shape we use.
type DomNode = {
  nodeType: number;
  nodeName: string;
  textContent: string | null;
  childNodes: ArrayLike<DomNode>;
};
type ReadabilityDoc = ConstructorParameters<typeof Readability>[0];

const ELEMENT_NODE = 1;
const TEXT_NODE = 3;

// R-M04 drops tables, figures, code blocks, captions and embedded media; the rest is page
// chrome that should never be read aloud when Readability finds no article.
const DROP = new Set([
  'TABLE',
  'FIGURE',
  'FIGCAPTION',
  'PRE',
  'PICTURE',
  'IMG',
  'VIDEO',
  'AUDIO',
  'IFRAME',
  'OBJECT',
  'EMBED',
  'SVG',
  'MATH',
  'CANVAS',
  'NOSCRIPT',
  'SCRIPT',
  'STYLE',
  'TEMPLATE',
  'FORM',
  'BUTTON',
  'SELECT',
  'TEXTAREA',
  'INPUT',
  'NAV',
  'ASIDE',
  'HEADER',
  'FOOTER',
]);
const BLOCK = new Set([
  'P',
  'DIV',
  'SECTION',
  'ARTICLE',
  'MAIN',
  'BLOCKQUOTE',
  'UL',
  'OL',
  'LI',
  'DL',
  'DT',
  'DD',
  'H1',
  'H2',
  'H3',
  'H4',
  'H5',
  'H6',
  'HR',
  'ADDRESS',
  'DETAILS',
  'SUMMARY',
  'BODY',
]);

function kindFor(tag: string): ParagraphKind {
  if (/^H[1-6]$/.test(tag)) return 'heading';
  return tag === 'LI' ? 'li' : 'p';
}

/** Emits one paragraph per run of inline text, each block element starting a new run. */
function collect(root: DomNode, kind: ParagraphKind, out: Paragraph[]): void {
  let buf = '';
  const flush = () => {
    const text = buf.replace(/\s+/g, ' ').trim();
    if (text) out.push({ kind, text });
    buf = '';
  };
  const visit = (node: DomNode): void => {
    if (node.nodeType === TEXT_NODE) {
      buf += node.textContent ?? '';
      return;
    }
    if (node.nodeType !== ELEMENT_NODE) return;
    const tag = node.nodeName.toUpperCase();
    if (DROP.has(tag)) return;
    if (tag === 'BR') {
      buf += ' ';
      return;
    }
    if (BLOCK.has(tag)) {
      flush();
      collect(node, kindFor(tag), out);
      return;
    }
    Array.from(node.childNodes).forEach(visit);
  };
  Array.from(root.childNodes).forEach(visit);
  flush();
}

const clean = (s: string | null | undefined) =>
  (s ?? '').replace(/\s+/g, ' ').trim();

function hostOf(url: string | undefined): string | undefined {
  const m = /^https?:\/\/(?:[^/?#@]*@)?([^/?#:]+)/i.exec(url ?? '');
  return m ? m[1].toLowerCase().replace(/^www\./, '') : undefined;
}

type Article = ReturnType<Readability['parse']>;

/**
 * linkedom gives an empty string no root element, and a fragment ("<p>...") the fragment's
 * own root, and its `title` and `body` getters then throw or read nothing; wrap both into a
 * full document.
 */
function parseDocument(html: string) {
  const { document } = parseHTML(html);
  if (document.documentElement?.nodeName.toUpperCase() === 'HTML')
    return document;
  return parseHTML(
    `<!doctype html><html><head></head><body>${html}</body></html>`,
  ).document;
}

export function extractArticle(html: string, url?: string): Extracted {
  const document = parseDocument(html);
  const pageTitle = clean(document.title);
  let article: Article = null;
  try {
    article = new Readability(document as unknown as ReadabilityDoc).parse();
  } catch {
    article = null;
  }

  const paragraphs: Paragraph[] = [];
  if (article?.content) {
    const { document: content } = parseHTML(
      `<!doctype html><html><body>${article.content}</body></html>`,
    );
    collect(content.body as unknown as DomNode, 'p', paragraphs);
  } else {
    // Readability mutates the document it reads, so the fallback reads a fresh parse.
    const fresh = parseDocument(html);
    if (fresh.body) collect(fresh.body as unknown as DomNode, 'p', paragraphs);
  }

  const chars = paragraphs.reduce((n, p) => n + p.text.length, 0);
  return {
    title: clean(article?.title) || pageTitle,
    site: clean(article?.siteName) || hostOf(url),
    byline: clean(article?.byline) || undefined,
    paragraphs,
    poor: paragraphs.length < POOR_MIN_PARAGRAPHS || chars < POOR_MIN_CHARS,
  };
}
