# ADR 0006: Skip Readability on pages whose depth-weighted size is over budget

- Status: accepted
- Date: 2026-10-01
- Deciders: owner chose to diagnose the F17 miss and fix it in JS (option A, REA-15); budget
  values set from device measurements
- Amends: srs.md R-M04 (adds an `extract-poor` cause)

## Context

R-M04 runs Mozilla Readability on-device; SPIKE-02's F17 line is 1.5 s for a page under 1 MB
and 10 s for a 5 MB page (median of 3, reference device, release build). The Phase 1 final
review and critique found that Readability's time is not bounded by page size alone.

Measured with `npm run device:devcheck` on the reference device (each fixture in a fresh
process, every launch at thermal status 0), build 82f15bc, 2026-10-01: Readability took
2122 ms for 300 KB of prose 60 levels deep and 3810 ms at 120 levels, against 769 ms for the
852 KB Gutenberg page at 7 levels. A 6000-level page stalled Readability for minutes in Node
and overflowed a recursive walk.

Readability reads the text under every wrapper element and walks each element's subtree, so
its cost tracks the sum, over every character and every element, of its nesting depth. On the
device that fit about 0.105 ms per thousand units plus 0.35 s, and Gutenberg (2.4 M units)
lies on the same line. Node did not reproduce the shape, so the calibration is the device's.

## Decision

`extractArticle` measures that sum with a pointer walk that stops once over budget, before
Readability runs. Over budget, Readability is skipped: the page's own text is read by the
fallback walk (page chrome dropped) and the item is `extract-poor`.

- Budget for a page under 1 MiB (by string length): 6,000,000 units, keeping Readability near
  1 s so the whole extraction fits 1.5 s.
- Budget for larger pages: 40,000,000 units; the 5 MB page measures 19 M.

## Consequences

- No page stalls the JS thread in Readability or overflows the stack. On build afec9ff the
  depth-60 and depth-120 pages extract in 161 and 158 ms, the 6000-level page in 1038 ms.
- Real fixtures are unchanged (byte-identical output): weather.gov 0.2 M, MDN 0.9 M,
  Wikipedia 1.4 M, Gutenberg 2.4 M units.
- Given up: a long article nested deep (for example 120,000 characters at depth 50) is read as
  `extract-poor` with the fallback's text instead of Readability's article. The budget is
  calibrated on synthetic pages; a real page whose cost is driven by something else (element
  count, as Wikipedia's partly is) is not bounded by it.
- Wikipedia "Speech synthesis" (705 KB) passes at 1387 ms, 92% of its line, so the under-1 MB
  line has little headroom on complex real pages.
