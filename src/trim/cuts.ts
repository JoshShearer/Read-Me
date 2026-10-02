import type { Position } from '../types';

// R-M05: cuts are a separate set of paragraph indices; the paragraphs themselves are never
// changed, so every cut is reversible (AGENTS.md 12). Each function returns a new set.

function check(index: number, count: number): void {
  if (!Number.isInteger(index) || index < 0 || index >= count) {
    throw new RangeError('paragraph index out of range');
  }
}

export function toggleCut(
  cuts: ReadonlySet<number>,
  index: number,
  count: number,
): Set<number> {
  check(index, count);
  const next = new Set(cuts);
  if (next.has(index)) next.delete(index);
  else next.add(index);
  return next;
}

export function cutAfter(
  cuts: ReadonlySet<number>,
  index: number,
  count: number,
): Set<number> {
  check(index, count);
  const next = new Set(cuts);
  for (let i = index + 1; i < count; i++) next.add(i);
  next.delete(index);
  return next;
}

export function startHere(
  cuts: ReadonlySet<number>,
  index: number,
  count: number,
): Set<number> {
  check(index, count);
  const next = new Set(cuts);
  for (let i = 0; i < index; i++) next.add(i);
  next.delete(index);
  return next;
}

export function keptIndices(
  cuts: ReadonlySet<number>,
  count: number,
): number[] {
  const kept: number[] = [];
  for (let i = 0; i < count; i++) if (!cuts.has(i)) kept.push(i);
  return kept;
}

/**
 * R-M05: after a cut change, the position stays put if its paragraph is still kept, else
 * moves to the start of the next kept paragraph. Null means nothing is left to read after it.
 */
export function remapPosition(
  position: Position,
  cuts: ReadonlySet<number>,
  count: number,
): Position | null {
  const p = position.paragraphIndex;
  if (p >= 0 && p < count && !cuts.has(p)) return position;
  for (let i = Math.max(p + 1, 0); i < count; i++) {
    if (!cuts.has(i)) return { paragraphIndex: i, charOffset: 0 };
  }
  return null;
}
