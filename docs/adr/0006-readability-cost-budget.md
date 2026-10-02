# ADR 0006: Readability runs on every real page; only a predicted stall skips it

- Status: accepted
- Date: 2026-10-02
- Deciders: owner (2026-10-02: diagnose the F17 miss and fix in JS; then "run anyway, guard
  stalls only"), model fitted to device measurements
- Amends: srs.md SPIKE-02 pass line (F17) and R-M04 (adds an `extract-poor` cause)

## Context

R-M04 runs Mozilla Readability on-device, in JS on Hermes, on the JS thread: while it runs the
screen does not respond. SPIKE-02's pass line (F17) was 1.5 s for every page under 1 MB and
10 s for a 5 MB page (median of 3, reference device, release build).

The Phase 1 reviews found Readability's time is not bounded by page size. Measured with
`npm run device:devcheck` (each fixture in a fresh process, every launch at thermal status 0,
reference device, build 54a216a, 2026-10-02), median Readability stage:

| Fixture | Size | Readability | Whole extract |
|---|---|---|---|
| Wikipedia "Speech synthesis" (local only, CC BY-SA) | 705 KB | 802 ms | 1122 ms |
| Gutenberg *Pride and Prejudice* | 852 KB | 733 ms | 1039 ms |
| 300 KB prose, 60 levels deep | 312 KB | 1649 ms | 1779 ms |
| 300 KB prose, 120 levels deep | 313 KB | 2933 ms | 3072 ms |
| 2000-comment thread | 570 KB | 3967 ms | 4336 ms |
| Link index | 972 KB | 1781 ms | 2357 ms |
| 5 MB page | 5.2 MB | 4846 ms | 6711 ms |
| 5 MB page, 4 levels deeper | 5.2 MB | 7062 ms | 8981 ms |

A bare 6000-level wrapper chain stalled Readability for minutes in Node (63 s at 2000 levels)
and overflowed a recursive walk.

Holding real pages to 1.5 s would mean skipping Readability on heavy pages (large Wikipedia
articles, forums) and reading them with page clutter. The owner chose extraction quality: slow
real pages run.

## Decision

1. F17's 1.5 s for a page under 1 MB is a target, measured and reported (MET or MISSED). The
   hard line is a stall ceiling: a median of 5 s for a page under 1 MB and 10 s above (the
   5 MB line is unchanged). `devcheck` fails on the ceiling, not the target.
2. Before Readability, `extractArticle` predicts its time with a pointer walk that stops once
   past the ceiling: 0.089 ms per thousand depth-weighted units (each character and element
   weighted by its nesting depth), 0.090 ms per element, and 0.455 ms more per container
   (div, section, article, main, ul, ol, table, form, aside, header, footer, nav). Script,
   style, noscript and template contents are not counted (Readability removes them first).
   The fit is least squares over the eleven fixtures above (plus MDN and two weather.gov
   pages), within 21% on every fixture over 0.5 s; holding the comment thread out, it
   over-predicts it.
3. Readability is skipped, and the item is `extract-poor` with the page's own text, when the
   prediction exceeds 4 s (page under 1 MiB of characters) or 8 s, or the page nests deeper
   than 200 levels (a bare wrapper chain costs more than linearly, which the fit never saw;
   real fixtures are 7-24 levels deep).

## Consequences

- Every fixture above runs Readability and stays under its ceiling; real pages meet F17
  (Wikipedia 1122 ms, Gutenberg 1039 ms). Synthetic stress pages miss it (1.8 to 4.3 s) and
  the screen is frozen that long; Phase 4's reader must show a busy state during extraction.
- A real heavy page may freeze the screen up to about 5 s before the guard skips Readability.
  Critique run D projected Wikipedia "Linux" (906 KB) and "Python" (987 KB) at roughly 1.6 and
  2.1 s from Node timings; not measured on the device.
- The model is calibrated on one device, one build, mostly synthetic shapes. A page whose cost
  comes from something else is not bounded by it. Moving extraction off the JS thread (Kotlin,
  or a JS worker) is the structural fix if real pages prove slower; not chosen now.
