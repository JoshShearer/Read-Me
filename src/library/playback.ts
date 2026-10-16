// The JS view of PlaybackService (R-M07, AGENTS.md 11). JS segments the kept text once per
// play and hands over the sentence list and a start; the service then owns the queue,
// position saves and the archive. Nothing here advances playback on an event. Never logs.
import { NativeEventEmitter } from 'react-native';
import Native, { type NativeEngine, type NativePlayback } from '../native/NativeReadMeSpeech';
import { sentenceIndexAt } from '../segment/locate';
import { segment, type SegmentOptions } from '../segment/segment';
import { remapPosition } from '../trim/cuts';
import type { Paragraph, Position, Sentence } from '../types';
import { getItem, setCuts, type Item } from './library';

// pastEnd: there was a saved position and nothing kept is left after it.
export type Plan = { sentences: Sentence[]; startIndex: number; pastEnd: boolean };

export type Playback = {
  itemId: number | null;
  // A Play the service holds while its engine starts (REA-35): no item is loaded yet.
  waitingItemId?: number | null;
  playing: boolean;
  sentence: { paragraphIndex: number; start: number; end: number } | null;
  rate: number;
  engine: string;
};

/**
 * R-M11: resume at the start of the sentence that contains the saved offset under today's
 * segmentation. A position in a paragraph cut since moves to the next kept one; a position
 * past the end starts over (pastEnd says so). Null when nothing is kept.
 */
export function plan(
  paragraphs: readonly Paragraph[],
  cuts: readonly number[],
  saved: Position | null,
  // Tests only: the parity golden (ADR 0011) pins the fallback path that Hermes runs.
  options: SegmentOptions = {},
): Plan | null {
  const cutSet = new Set(cuts);
  const sentences = segment(paragraphs, cutSet, options);
  if (sentences.length === 0) return null;
  const moved = saved && remapPosition(saved, cutSet, paragraphs.length);
  const i = moved ? sentenceIndexAt(sentences, moved) : 0;
  return { sentences, startIndex: i < 0 ? 0 : i, pastEnd: saved !== null && (!moved || i < 0) };
}

async function planFor(id: number): Promise<{ title: string; plan: Plan } | null> {
  const detail = await getItem(id);
  if (detail === null) return null;
  const p = plan(detail.paragraphs, detail.cuts, await Native.getPosition(id));
  return p === null ? null : { title: detail.item.title, plan: p };
}

export async function playItem(id: number): Promise<boolean> {
  const p = await planFor(id);
  if (p === null) return false;
  return Native.play(id, p.title, p.plan.sentences, p.plan.startIndex);
}

/**
 * The Reader's Play/Pause for item `id`, where `current` is the screen's copy of the service's
 * state (the button showed Pause when it says this item plays). Decided on the service's state
 * read now as well (REA-55), since the copy can be one event behind, and never so that a stale
 * view replaces the item being read with an earlier (finished, archived) one:
 * - A tap on Pause is a Pause, whatever happened since: it pauses what plays, and does nothing
 *   if nothing plays (the item ended, Stop, or a pause from elsewhere); the event that follows
 *   refreshes the view. It never starts or resumes anything (fix round 1: an idle service once
 *   made it play the finished item again).
 * - A tap on Play when the view showed this item but the service has moved to another is
 *   dropped; the event that follows moves the Reader to the new item.
 * - Otherwise: pause this item if the service is playing it (the copy had not caught up),
 *   resume it if the service holds it paused, else play it (false from resume: the service is
 *   gone, so play again from the saved position).
 * If the service's state cannot be read, the screen's copy decides alone.
 */
export async function toggle(id: number, current: Playback | null): Promise<boolean> {
  const fresh = await getPlayback().catch(() => null);
  if (current?.itemId === id && current.playing) {
    return fresh === null || fresh.playing ? Native.pause() : false;
  }
  const now = fresh ?? current;
  if (current?.itemId === id && now?.itemId != null && now.itemId !== id) return false;
  if (now?.itemId === id) {
    if (now.playing) return Native.pause();
    if (await Native.resume()) return true;
  }
  return playItem(id);
}

export const pause = () => Native.pause();
export const resume = () => Native.resume();
export const next = () => Native.next();
export const previous = () => Native.previous();
export const backParagraph = () => Native.backParagraph();
export const setRate = (rate: number) => Native.setRate(rate);

export function toPlayback(n: NativePlayback): Playback {
  return {
    itemId: n.itemId,
    waitingItemId: n.waitingItemId ?? null,
    playing: n.playing,
    sentence:
      n.paragraphIndex < 0
        ? null
        : { paragraphIndex: n.paragraphIndex, start: n.start, end: n.end },
    rate: n.rate,
    engine: n.engine,
  };
}

export async function getPlayback(): Promise<Playback> {
  return toPlayback(await Native.getPlayback());
}

export function onPlayback(cb: (p: Playback) => void): () => void {
  const sub = new NativeEventEmitter(Native).addListener(
    'ReadMePlayback',
    (n: unknown) => cb(toPlayback(n as NativePlayback)),
  );
  return () => sub.remove();
}

/**
 * R-S05, REA-55: the service moved from one item straight to another, which only a
 * continuous-play handover does without a Play from this Reader. ADR 0011's handover never
 * publishes "no item" between the two (a pause or Stop in its window does, and ends the chain),
 * and every JS start is for the Reader's or Trim's own item. A Play that was waiting for the
 * engine (REA-35) is not a handover. A future control that starts another item while a Reader
 * is open must revisit this.
 */
/**
 * REA-39: the service's item id while it reads a note for Obsidian (PlaybackService.BRIDGE_ITEM).
 * It is not in the store: no screen opens it and nothing follows to or from it.
 */
export const BRIDGE_ITEM = -1;

export function handedOver(prev: Playback | null, now: Playback): { from: number; to: number } | null {
  if (prev?.itemId == null || now.itemId == null || prev.itemId === now.itemId) return null;
  if (prev.itemId === BRIDGE_ITEM || now.itemId === BRIDGE_ITEM) return null;
  if (now.itemId === prev.waitingItemId) return null;
  return { from: prev.itemId, to: now.itemId };
}

export function marker(item: Item, p: Playback | null): string {
  if (p?.itemId === item.id) return p.playing ? 'playing' : 'paused';
  return item.archivedAt === undefined ? '' : 'archived';
}

/** R-M06: no engine bound, or no offline voice. */
export function engineProblem(p: Playback | null): boolean {
  return engineBlocked(p?.engine);
}

export const stop = () => Native.stop();

/**
 * R-M05 + srs "Playback": store the new cut set; if the service holds this item, it must not
 * go on reading paragraphs that are now cut. Playing: hand it the new list from the saved
 * position (remapped by plan()). Paused, everything cut, or nothing kept after the position:
 * stop, so nothing cut is read and a finished stretch does not restart from the top. The
 * service's state is read here: a screen's copy can be stale or not loaded yet. A Play waiting
 * for the engine counts as playing (REA-35 #4): a new play replaces the waiting request, and a
 * stop cancels it. A Play waiting for another item is left alone.
 */
export async function applyCuts(id: number, cuts: ReadonlySet<number>): Promise<void> {
  await setCuts(id, [...cuts].sort((a, b) => a - b));
  const current = await getPlayback().catch(() => null);
  if (current === null) return;
  // Another item's Play waits for the engine and will replace this one: stopping here would
  // cancel that Play. The stored cuts apply when this item is next played.
  if (current.waitingItemId != null && current.waitingItemId !== id) return;
  const waiting = current.waitingItemId === id;
  if (current.itemId !== id && !waiting) return;
  if (current.playing || waiting) {
    const p = await planFor(id);
    if (p !== null && !p.plan.pastEnd) {
      await Native.play(id, p.title, p.plan.sentences, p.plan.startIndex);
      return;
    }
  }
  await Native.stop();
}

export type Engine = NativeEngine;

export const getEngine = (): Promise<Engine> => Native.getEngine();
export const setVoice = (name: string | null) => Native.setVoice(name);
/** Below Android 14 only; clears the voice, since a voice belongs to its engine. */
export const setEngine = (pkg: string | null) => Native.setEngine(pkg);

export const NO_ENGINE: Engine = {
  status: 'no-engine',
  voices: [],
  selected: null,
  engine: null,
  engineLabel: null,
  choosable: false,
  engines: [],
};
export const getNotices = () => Native.getNotices();

/** R-S05: whether PlaybackService goes on to the next unread item when one ends. */
export const getContinuousPlay = (): Promise<boolean> => Native.getContinuousPlay();
export const setContinuousPlay = (on: boolean): Promise<boolean> => Native.setContinuousPlay(on);

/** R-M06 / R-M10: no engine bound, or no offline voice. */
export function engineBlocked(status: string | undefined): boolean {
  return status === 'no-engine' || status === 'no-voice';
}
