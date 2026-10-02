import type { Position, Sentence } from '../types';

// R-M11: resume at the start of the sentence containing the saved offset, under the current
// segmentation. The returned index is only valid for this `sentences` array; never persist it.
export function sentenceIndexAt(
  sentences: readonly Sentence[],
  position: Position,
): number {
  for (let i = 0; i < sentences.length; i++) {
    const s = sentences[i];
    if (s.paragraphIndex < position.paragraphIndex) continue;
    if (s.paragraphIndex > position.paragraphIndex) return i;
    if (position.charOffset < s.end) return i;
  }
  return -1;
}

export function positionOf(sentence: Sentence): Position {
  return {
    paragraphIndex: sentence.paragraphIndex,
    charOffset: sentence.start,
  };
}
