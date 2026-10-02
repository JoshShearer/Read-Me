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

/** Per-stage wall time in ms, filled only when a caller (the devcheck) passes an object. */
export type ExtractTimings = Partial<
  Record<'parse' | 'depth' | 'readability' | 'contentParse' | 'walk', number>
>;

export const POOR_MIN_PARAGRAPHS = 3;
export const POOR_MIN_CHARS = 500;
// Readability's time on Hermes, predicted before it runs (ADR 0006). It re-reads the text
// under every wrapper element, so each character costs its nesting depth, and it does fixed
// work per element. Least-squares fit to eight devcheck fixtures on the reference device
// (82f15bc, afec9ff): 0.107 ms per thousand depth-weighted units (a character or an element,
// each weighted by its depth) plus 0.11 ms per element; within 22% on the real pages and the
// 5 MB page. Slow pages still run (F17's 1.5 s is a target, owner decision 2026-10-02); only a
// page predicted past the stall ceiling skips Readability and is read as poor.
export const MS_PER_KILO_UNIT = 0.107;
export const MS_PER_ELEMENT = 0.11;
// A bare wrapper chain costs Readability more than linearly (Node: 0.8 s at 300 levels, 63 s
// at 2000), which the fit never saw, so depth has its own ceiling. Real pages measured 7-23
// levels (devcheck fixtures).
export const MAX_READABILITY_DEPTH = 200;
export const STALL_CEILING_MS_SMALL = 4000;
export const STALL_CEILING_MS_LARGE = 8000;
const LARGE_PAGE_CHARS = 1024 * 1024;

// The TS lib here has no DOM types; this is the part of linkedom's node shape we use.
type DomNode = {
  nodeType: number;
  nodeName: string;
  textContent: string | null;
  childNodes: ArrayLike<DomNode>;
  firstChild: DomNode | null;
  nextSibling: DomNode | null;
  parentNode: DomNode | null;
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

// Readability removes these before it scores anything, so their text costs it nothing.
const BUDGET_SKIP = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEMPLATE']);

/**
 * True when Readability's predicted time on the page under `root` exceeds `ceilingMs`, or the
 * page nests deeper than MAX_READABILITY_DEPTH.
 * Walks sibling pointers, not child arrays (it visits every node of a 5 MB page), and stops as
 * soon as the ceiling is passed, so a 6000-level page costs almost nothing here.
 */
function predictedOver(root: DomNode, ceilingMs: number): boolean {
  let ms = 0;
  let depth = 0;
  let node: DomNode | null = root;
  while (node) {
    if (node.nodeType === TEXT_NODE)
      ms += ((node.textContent ?? '').length * depth * MS_PER_KILO_UNIT) / 1000;
    else if (node.nodeType === ELEMENT_NODE)
      ms += (depth * MS_PER_KILO_UNIT) / 1000 + MS_PER_ELEMENT;
    if (ms > ceilingMs || depth > MAX_READABILITY_DEPTH) return true;
    const child: DomNode | null =
      node.nodeType === ELEMENT_NODE &&
      !BUDGET_SKIP.has(node.nodeName.toUpperCase())
        ? node.firstChild
        : null;
    if (child) {
      node = child;
      depth++;
      continue;
    }
    while (node && node !== root && !node.nextSibling) {
      node = node.parentNode;
      depth--;
    }
    if (!node || node === root) return false;
    node = node.nextSibling;
  }
  return false;
}

const clean = (s: string | null | undefined) =>
  (s ?? '').replace(/\s+/g, ' ').trim();

function hostOf(url: string | undefined): string | undefined {
  const m = /^https?:\/\/(?:[^/?#@]*@)?([^/?#:]+)/i.exec(url ?? '');
  return m ? m[1].toLowerCase().replace(/^www\./, '') : undefined;
}

type Article = ReturnType<Readability<DomNode>['parse']>;

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

export function extractArticle(
  html: string,
  url?: string,
  timings?: ExtractTimings,
): Extracted {
  let mark = Date.now();
  const lap = (stage: keyof ExtractTimings) => {
    const now = Date.now();
    if (timings) timings[stage] = (timings[stage] ?? 0) + (now - mark);
    mark = now;
  };
  const document = parseDocument(html);
  const pageTitle = clean(document.title);
  lap('parse');
  const tooDeep = predictedOver(
    document.documentElement as unknown as DomNode,
    html.length < LARGE_PAGE_CHARS
      ? STALL_CEILING_MS_SMALL
      : STALL_CEILING_MS_LARGE,
  );
  lap('depth');
  let article: Article = null;
  if (!tooDeep) {
    try {
      // The serializer hands back Readability's own content node. The default serializes it
      // to HTML, which then had to be parsed again (1.5 s of a 5 MB page on the device).
      article = new Readability<DomNode>(
        document as unknown as ReadabilityDoc,
        {
          serializer: node => node as unknown as DomNode,
        },
      ).parse();
    } catch {
      article = null;
    }
  }
  lap('readability');

  const paragraphs: Paragraph[] = [];
  if (article?.content) {
    lap('contentParse');
    collect(article.content, 'p', DROP, paragraphs);
  } else {
    // Readability mutates the document it reads, so the fallback reads a fresh parse unless
    // Readability never ran.
    const fresh = tooDeep ? document : parseDocument(html);
    lap('contentParse');
    if (fresh.body)
      collect(fresh.body as unknown as DomNode, 'p', DROP_FALLBACK, paragraphs);
  }
  lap('walk');

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
