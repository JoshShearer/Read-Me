// The decisions behind the screens, kept pure so Jest can pin them. Screens render; this
// decides. Nothing here reaches native code.
import type { Item } from '../library/library';
import type { Paragraph } from '../types';

export type Route =
  | { name: 'list' }
  | { name: 'trim'; id: number }
  | { name: 'reader'; id: number }
  | { name: 'settings' }
  | { name: 'licenses' };

export const push = (stack: readonly Route[], r: Route): Route[] => [...stack, r];

/** The hardware back button: pop, or null on the list (let Android leave the app). */
export const back = (stack: readonly Route[]): Route[] | null =>
  stack.length > 1 ? stack.slice(0, -1) : null;

/** Trim's Done: back to the Reader below it, else (a first open) replace Trim with one. */
export function trimDone(stack: readonly Route[], id: number): Route[] {
  const below = stack[stack.length - 2];
  if (below?.name === 'reader' && below.id === id) return stack.slice(0, -1);
  return [...stack.slice(0, -1), { name: 'reader', id }];
}

/** R-M05: Trim opens automatically the first time an item is opened. */
export const openRoute = (item: Item): Route =>
  item.openedAt === undefined ? { name: 'trim', id: item.id } : { name: 'reader', id: item.id };

export const visibleItems = (items: readonly Item[], archive: boolean): Item[] =>
  items.filter(i => (i.archivedAt !== undefined) === archive);

export type Action = 'open' | 'retry' | 'restore' | 'delete';

export const ACTION_LABEL: Record<Action, string> = {
  open: 'Read',
  retry: 'Retry',
  restore: 'Restore',
  delete: 'Delete',
};

/** R-M10's table. extract-poor's "Read anyway" is `open`. */
export function actions(item: Item): Action[] {
  if (item.archivedAt !== undefined) return ['open', 'restore', 'delete'];
  switch (item.state) {
    case 'fetch-failed':
      return ['retry', 'delete'];
    case 'extract-poor':
    case 'ready':
      return ['open', 'delete'];
    default:
      return ['delete'];
  }
}

export const SHARE_TEXT_GUIDANCE =
  'If this keeps failing, open the page in your browser, select the article, and share the text to Read Me instead.';

export const guidance = (item: Item): string | null =>
  item.state === 'fetch-failed' || item.state === 'extract-poor' ? SHARE_TEXT_GUIDANCE : null;

export function subtitle(item: Item): string {
  const parts: string[] = [];
  const source = item.site ?? (item.kind === 'text' ? 'Shared text' : undefined);
  if (source) parts.push(source);
  if (item.words > 0) parts.push(`${item.words} ${item.words === 1 ? 'word' : 'words'}`);
  if (item.progress > 0 && item.progress < 1) parts.push(`${Math.round(item.progress * 100)}% read`);
  return parts.join(' · ');
}

export const badge = (item: Item): string =>
  item.failReason ? `${item.state}: ${item.failReason}` : item.state;

export type ReaderParagraph = {
  index: number;
  text: string;
  current: { start: number; end: number } | null;
};

export function readerParagraphs(
  paragraphs: readonly Paragraph[],
  cuts: readonly number[],
  sentence: { paragraphIndex: number; start: number; end: number } | null,
): ReaderParagraph[] {
  const cut = new Set(cuts);
  const out: ReaderParagraph[] = [];
  paragraphs.forEach((p, index) => {
    if (cut.has(index)) return;
    const current =
      sentence && sentence.paragraphIndex === index
        ? { start: sentence.start, end: sentence.end }
        : null;
    out.push({ index, text: p.text, current });
  });
  return out;
}

/** R-M07: 0.5x to 4.0x in 0.1x steps. */
export function stepRate(rate: number, dir: 1 | -1): number {
  const next = Math.round(rate * 10 + dir) / 10;
  return Math.min(4, Math.max(0.5, next));
}

export const formatRate = (rate: number): string => `${rate.toFixed(1)}x`;

export type Notice = { name: string; version: string; license: string; url?: string; text?: string };

export function parseNotices(json: string): Notice[] {
  try {
    const d = JSON.parse(json) as { npm?: Notice[]; android?: Notice[] };
    return [...(d.npm ?? []), ...(d.android ?? [])].sort((a, b) => a.name.localeCompare(b.name));
  } catch {
    return [];
  }
}
