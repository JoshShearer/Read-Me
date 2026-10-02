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
// Readability's time grows steeply with nesting depth (Node: 0.2 s at 100 levels, 0.8 s at 300,
// 63 s at 2000), so a deeper page skips it and is read as poor. The saved real pages are 7-15
// levels deep.
export const MAX_READABILITY_DEPTH = 128;

// The TS lib here has no DOM types; this is the part of linkedom's node shape we use.
type DomNode = {
  nodeType: number;
  nodeName: string;
  textContent: string | null;
  childNodes: ArrayLike<DomNode>;
  firstElementChild: DomNode | null;
  nextElementSibling: DomNode | null;
  parentElement: DomNode | null;
};
type ReadabilityDoc = ConstructorParameters<typeof Readability>[0];

const ELEMENT_NODE = 1;
const TEXT_NODE = 3;

// R-M04 drops tables, figures, code blocks, captions and embedded media; scripts, styles and
// form controls hold nothing to read.
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
]);
// Page chrome, dropped only when Readability found no article. Inside an article a <header>
// holds the lead heading and standfirst, which are content.
const DROP_FALLBACK = new Set([...DROP, 'NAV', 'ASIDE', 'HEADER', 'FOOTER']);
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

type Step =
  | { node: DomNode; kind: ParagraphKind }
  | { node: null; kind: ParagraphKind };

/**
 * Emits one paragraph per run of inline text, each block element starting a new run. Walks
 * with an explicit stack: page depth is untrusted input and must not reach the call stack.
 */
function collect(
  root: DomNode,
  kind: ParagraphKind,
  drop: Set<string>,
  out: Paragraph[],
): void {
  let buf = '';
  const flush = (k: ParagraphKind) => {
    const text = buf.replace(/\s+/g, ' ').trim();
    if (text) out.push({ kind: k, text });
    buf = '';
  };
  const pushChildren = (node: DomNode, k: ParagraphKind, stack: Step[]) => {
    const children = Array.from(node.childNodes);
    for (let i = children.length - 1; i >= 0; i--)
      stack.push({ node: children[i], kind: k });
  };
  // A step with node null is the end of a block: flush its run with the block's kind.
  const stack: Step[] = [{ node: null, kind }];
  pushChildren(root, kind, stack);
  while (stack.length > 0) {
    const step = stack.pop() as Step;
    const node = step.node;
    if (node === null) {
      flush(step.kind);
      continue;
    }
    if (node.nodeType === TEXT_NODE) {
      buf += node.textContent ?? '';
      continue;
    }
    if (node.nodeType !== ELEMENT_NODE) continue;
    const tag = node.nodeName.toUpperCase();
    if (drop.has(tag)) continue;
    if (tag === 'BR') {
      buf += ' ';
      continue;
    }
    if (BLOCK.has(tag)) {
      flush(step.kind);
      const inner = kindFor(tag);
      stack.push({ node: null, kind: inner });
      pushChildren(node, inner, stack);
      continue;
    }
    pushChildren(node, step.kind, stack);
  }
}

/**
 * True when element nesting under `root` exceeds `limit`. Walks element pointers, not child
 * arrays: it visits every element of a 5 MB page, and allocating per node showed in timings.
 */
function deeperThan(root: DomNode, limit: number): boolean {
  let node: DomNode | null = root;
  let depth = 0;
  while (node) {
    if (depth > limit) return true;
    const child: DomNode | null = node.firstElementChild;
    if (child) {
      node = child;
      depth++;
      continue;
    }
    while (node && node !== root && !node.nextElementSibling) {
      node = node.parentElement;
      depth--;
    }
    if (!node || node === root) return false;
    node = node.nextElementSibling;
  }
  return false;
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
  const tooDeep = deeperThan(
    document.documentElement as unknown as DomNode,
    MAX_READABILITY_DEPTH,
  );
  let article: Article = null;
  if (!tooDeep) {
    try {
      article = new Readability(document as unknown as ReadabilityDoc).parse();
    } catch {
      article = null;
    }
  }

  const paragraphs: Paragraph[] = [];
  if (article?.content) {
    const { document: content } = parseHTML(
      `<!doctype html><html><body>${article.content}</body></html>`,
    );
    collect(content.body as unknown as DomNode, 'p', DROP, paragraphs);
  } else {
    // Readability mutates the document it reads, so the fallback reads a fresh parse unless
    // Readability never ran.
    const fresh = tooDeep ? document : parseDocument(html);
    if (fresh.body)
      collect(fresh.body as unknown as DomNode, 'p', DROP_FALLBACK, paragraphs);
  }

  const chars = paragraphs.reduce((n, p) => n + p.text.length, 0);
  return {
    title: clean(article?.title) || pageTitle,
    site: clean(article?.siteName) || hostOf(url),
    byline: clean(article?.byline) || undefined,
    paragraphs,
    poor:
      tooDeep ||
      paragraphs.length < POOR_MIN_PARAGRAPHS ||
      chars < POOR_MIN_CHARS,
  };
}
