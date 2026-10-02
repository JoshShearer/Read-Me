// The TS library facade (ADR 0001): every read and write goes through ReadMeSpeech; JS never
// holds the database. drainFetched is ADR 0007's JS half: items the native side stored as
// "fetched" are extracted here (Phase 1 extract) and handed back. Never logs.
import { NativeEventEmitter } from 'react-native';
import Native, {
  type NativeItem,
  type NativeParagraph,
} from '../native/NativeReadMeSpeech';
import { extractArticle, type Extracted } from '../extract/extract';
import type { Paragraph, ParagraphKind } from '../types';

export type ItemState =
  | 'fetching'
  | 'fetched'
  | 'fetch-failed'
  | 'extract-poor'
  | 'ready';

export type Item = {
  id: number;
  kind: 'link' | 'text';
  url?: string;
  title: string;
  site?: string;
  byline?: string;
  createdAt: number;
  state: ItemState;
  failReason?: string;
  openedAt?: number;
  archivedAt?: number;
};

export type ItemDetail = { item: Item; paragraphs: Paragraph[]; cuts: number[] };

type Extract = (html: string, url?: string) => Pick<
  Extracted,
  'title' | 'paragraphs' | 'poor'
> &
  Partial<Pick<Extracted, 'site' | 'byline'>>;

const opt = <T>(v: T | null): T | undefined => (v === null ? undefined : v);

export function toItem(n: NativeItem): Item {
  return {
    id: n.id,
    kind: n.kind as Item['kind'],
    url: opt(n.url),
    title: n.title,
    site: opt(n.site),
    byline: opt(n.byline),
    createdAt: n.createdAt,
    state: n.state as ItemState,
    failReason: opt(n.failReason),
    openedAt: opt(n.openedAt),
    archivedAt: opt(n.archivedAt),
  };
}

export async function listItems(): Promise<Item[]> {
  return (await Native.listItems()).map(toItem);
}

export async function getItem(id: number): Promise<ItemDetail | null> {
  const d = await Native.getItem(id);
  if (d === null) return null;
  return {
    item: toItem(d.item),
    paragraphs: d.paragraphs.map(p => ({
      kind: p.kind as ParagraphKind,
      text: p.text,
    })),
    cuts: d.cuts,
  };
}

async function drainItem(item: Item, extract: Extract): Promise<boolean> {
  // Null when the body is gone; the native side has already failed the item.
  const html = await Native.getBody(item.id);
  if (html === null) return false;
  let ex: ReturnType<Extract>;
  try {
    ex = extract(html, item.url);
  } catch {
    ex = { title: '', paragraphs: [], poor: true };
  }
  const paragraphs: NativeParagraph[] = ex.paragraphs.map(p => ({
    kind: p.kind,
    text: p.text,
  }));
  return Native.completeExtraction(
    item.id,
    ex.title || item.title,
    ex.site ?? null,
    ex.byline ?? null,
    paragraphs,
    ex.poor,
  );
}

async function drainOnce(extract: Extract): Promise<number> {
  let n = 0;
  for (const item of await listItems()) {
    if (item.state !== 'fetched') continue;
    // One item's native failure (deleted mid-drain, a storage error) must not hold up the rest;
    // the item stays fetched and the next drain tries it again.
    try {
      if (await drainItem(item, extract)) n++;
    } catch {}
  }
  return n;
}

let running: Promise<number> | null = null;
let again = false;

/**
 * Extracts every fetched item. A call while a drain runs makes that drain go round again, so
 * an item fetched mid-drain is not left until the next app start (Review Focus 2).
 */
export function drainFetched(extract: Extract = extractArticle): Promise<number> {
  if (running) {
    again = true;
    return running;
  }
  running = (async () => {
    let total = 0;
    do {
      again = false;
      total += await drainOnce(extract);
    } while (again);
    return total;
  })().finally(() => {
    running = null;
  });
  return running;
}

export const retryFetch = (id: number) => Native.retryFetch(id);
export const deleteItem = (id: number) => Native.deleteItem(id);
export const markOpened = (id: number) => Native.markOpened(id);
export const setCut = (id: number, paragraphIndex: number, cut: boolean) =>
  Native.setCut(id, paragraphIndex, cut);
export const archiveItem = (id: number) => Native.archiveItem(id);
export const restoreItem = (id: number) => Native.restoreItem(id);

export function onItemsChanged(cb: () => void): () => void {
  const sub = new NativeEventEmitter(Native).addListener('ReadMeItemsChanged', cb);
  return () => sub.remove();
}
