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

/** A row tap: pause what plays, resume what is paused, otherwise play this item. */
export async function toggle(id: number, current: Playback | null): Promise<boolean> {
  if (current?.itemId === id) {
    if (current.playing) return Native.pause();
    // False when the service is gone (process restarted): play again from the saved position.
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
