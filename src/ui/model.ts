// The decisions behind the screens, kept pure so Jest can pin them. Screens render; this
// decides. Nothing here reaches native code.
import type { Item } from '../library/library';
import type { Paragraph } from '../types';

export type Route =
  | { name: 'list' }
  // `reading`: the item the service handed over to while this Trim was open on the item being
  // read (REA-55 fix round 1); Done opens its Reader.
  | { name: 'trim'; id: number; reading?: number }
  | { name: 'reader'; id: number }
  | { name: 'settings' }
  | { name: 'licenses' };

export const push = (stack: readonly Route[], r: Route): Route[] => [...stack, r];

/** The hardware back button: pop, or null on the list (let Android leave the app). */
export const back = (stack: readonly Route[]): Route[] | null =>
  stack.length > 1 ? stack.slice(0, -1) : null;

/**
 * Trim's Done: back to the Reader below it, else (a first open) replace Trim with one. The
 * Reader below may show another item: it followed a continuous-play handover while Trim was
 * open (REA-55), and it stays on the item being read. On a first open, the Reader is Trim's own
 * item, unless the service handed over away from it while Trim was open: then it is the item now
 * being read (`reading`, set by followHandover), so its Play acts on that item, never replaces it
 * with the finished one. A trim route only ever sits on its own Reader or on the List.
 */
export function trimDone(stack: readonly Route[], id: number): Route[] {
  const below = stack[stack.length - 2];
  if (below?.name === 'reader') return stack.slice(0, -1);
  const top = stack[stack.length - 1];
  const to = top?.name === 'trim' && top.reading !== undefined ? top.reading : id;
  return [...stack.slice(0, -1), { name: 'reader', id: to }];
}

/**
 * R-S05, REA-55 (ADR 0011): the service handed over from item `from` to item `to`, so a Reader
 * showing `from` becomes the Reader of `to`. Back still pops to what was below it. A Trim of the
 * item being read stays on that item (the user is editing it; cuts are saved to it) and only
 * records `to` for its Done; a Trim of any other item is left alone. Not an open: nothing is
 * marked opened. The same stack when nothing matches, so nothing re-renders.
 */
export function followHandover(stack: Route[], from: number, to: number): Route[] {
  const reader = (r: Route) => r.name === 'reader' && r.id === from;
  const trim = (r: Route) => r.name === 'trim' && (r.reading ?? r.id) === from;
  if (!stack.some(r => reader(r) || trim(r))) return stack;
  return stack.map(r => {
    if (reader(r)) return { name: 'reader', id: to };
    if (trim(r)) return { name: 'trim', id: (r as { id: number }).id, reading: to };
    return r;
  });
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
export const RATE_MIN = 0.5;
export const RATE_MAX = 4;

export function stepRate(rate: number, dir: 1 | -1): number {
  const next = Math.round(rate * 10 + dir) / 10;
  return Math.min(RATE_MAX, Math.max(RATE_MIN, next));
}

/**
 * R-M07 "kept in view": the y of the laid-out line holding character `offset`, from a Text's
 * onTextLayout lines (each line's text is its slice of the paragraph). 0 when unknown.
 */
export function lineTop(lines: readonly { text: string; y: number }[], offset: number): number {
  let end = 0;
  for (const l of lines) {
    end += l.text.length;
    if (offset < end) return l.y;
  }
  return lines.length ? lines[lines.length - 1].y : 0;
}

export const formatRate = (rate: number): string => `${rate.toFixed(1)}x`;

export type Notice = { name: string; version: string; license: string; url?: string; text?: string };

export function parseNotices(json: string): Notice[] {
  try {
    const d = JSON.parse(json) as { npm?: Notice[]; android?: Notice[]; native?: Notice[] };
    return [...(d.npm ?? []), ...(d.android ?? []), ...(d.native ?? [])].sort((a, b) => a.name.localeCompare(b.name));
  } catch {
    return [];
  }
}

// R-M12: the bridge port is configurable within the unprivileged range.
export const BRIDGE_PORT_MIN = 1024;
export const BRIDGE_PORT_MAX = 65535;

export function parsePort(s: string): number | null {
  const t = s.trim();
  if (!/^\d{1,5}$/.test(t)) return null;
  const n = Number(t);
  return n >= BRIDGE_PORT_MIN && n <= BRIDGE_PORT_MAX ? n : null;
}

export function bridgeStatus(b: { enabled: boolean; state: string; port: number; error: string | null }): string {
  if (!b.enabled) return 'Off';
  if (b.state === 'on') return `On at 127.0.0.1:${b.port}`;
  if (b.state === 'failed') {
    return b.error === 'BindException'
      ? `Port ${b.port} is in use by another app. Choose another port.`
      : `The bridge could not start (${b.error ?? 'unknown'}).`;
  }
  return 'Starting...';
}

// REA-21 (e): Settings' voice list starts with the engine's own default, so a chosen voice can
// be undone. The value is not a voice name; Settings maps it to setVoice(null). Offline voices
// only (R-M06): the default resolves through the same VoicePicker as any choice.
export const ENGINE_DEFAULT = 'engine-default';

type EngineVoices = {
  voices: readonly { name: string; label: string; detail: string }[];
  selected: string | null;
  preferred?: string | null;
};

export function voiceOptions(engine: EngineVoices | null): { value: string; label: string; detail?: string }[] {
  if (!engine) return [];
  return [
    { value: ENGINE_DEFAULT, label: 'Engine default', detail: "The engine's own choice" },
    ...engine.voices.map(v => ({ value: v.name, label: v.label, detail: v.detail || undefined })),
  ];
}

export function voiceValue(engine: EngineVoices | null): string | null {
  if (!engine) return null;
  return engine.preferred ? engine.selected : ENGINE_DEFAULT;
}
