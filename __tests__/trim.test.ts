import {
  cutAfter,
  keptIndices,
  remapPosition,
  startHere,
  toggleCut,
} from '../src/trim/cuts';

const sorted = (s: ReadonlySet<number>) => [...s].sort((a, b) => a - b);

describe('cut operations (R-M05)', () => {
  test('toggle cuts and restores one paragraph, without mutating input', () => {
    const none = new Set<number>();
    const one = toggleCut(none, 2, 5);
    expect(sorted(one)).toEqual([2]);
    expect(sorted(none)).toEqual([]);
    expect(sorted(toggleCut(one, 2, 5))).toEqual([]);
  });

  test('cut everything after keeps the anchor and earlier cuts', () => {
    expect(sorted(cutAfter(new Set([0, 2]), 2, 6))).toEqual([0, 3, 4, 5]);
  });

  test('start here cuts everything before and keeps the anchor', () => {
    expect(sorted(startHere(new Set([1, 4]), 3, 6))).toEqual([0, 1, 2, 4]);
  });

  test('kept indices are the complement, in order', () => {
    expect(keptIndices(new Set([1, 3]), 5)).toEqual([0, 2, 4]);
  });

  test('an index out of range throws a fixed message', () => {
    expect(() => toggleCut(new Set(), 5, 5)).toThrow(
      'paragraph index out of range',
    );
    expect(() => cutAfter(new Set(), -1, 5)).toThrow(
      'paragraph index out of range',
    );
  });
});

describe('remapPosition (R-M05, R-M11)', () => {
  const pos = (paragraphIndex: number, charOffset: number) => ({
    paragraphIndex,
    charOffset,
  });

  test('a kept paragraph keeps the exact position', () => {
    expect(remapPosition(pos(2, 17), new Set([0, 1]), 5)).toEqual(pos(2, 17));
  });

  test('a cut paragraph moves to the start of the next kept one', () => {
    expect(remapPosition(pos(2, 17), new Set([2, 3]), 5)).toEqual(pos(4, 0));
  });

  test('nothing kept after the position gives null', () => {
    expect(
      remapPosition(pos(2, 17), cutAfter(new Set([2]), 1, 5), 5),
    ).toBeNull();
    expect(remapPosition(pos(0, 0), new Set([0, 1, 2]), 3)).toBeNull();
  });

  test('a position beyond the item gives null', () => {
    expect(remapPosition(pos(9, 0), new Set(), 3)).toBeNull();
  });
});
