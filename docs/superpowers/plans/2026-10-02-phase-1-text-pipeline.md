# Phase 1: Text Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pure TypeScript modules that turn a share into paragraphs and sentences: classify the shared text, extract an article from HTML, split shared text into paragraphs, segment paragraphs into sentences with character offsets, and apply non-destructive trim cuts with position remapping. All of it runs on Hermes in the release build, which a device check proves.

**Architecture:** Five small modules under `src/` (`intake`, `extract`, `segment`, `trim`, plus shared `types.ts`). None of them touch the network, storage, playback or the UI. Phase 2 (native Store, Fetcher, share activity) and Phase 4 (screens) call them. A `devcheck` entry point (a separate JS bundle, never in the product build) runs the real pipeline on the phone and compares its output, byte for byte, with Node's (roadmap F19).

**Tech Stack:** React Native 0.87.1, Hermes, TypeScript (strict), Jest, `@mozilla/readability` 0.6.0, `linkedom` 0.18.13, `@babel/plugin-transform-export-namespace-from` 7.29.7.

**Spec:** `srs.md` (R-M02, R-M04, R-M05, R-M08, R-M09, R-M11, R-M14; SPIKE-02 and SPIKE-03 answers). Roadmap: `docs/superpowers/plans/2026-10-01-roadmap.md`, Phase 1 row and F19.

## Global Constraints

- **No item text in any log** (AGENTS.md 1). The pipeline never logs. Error messages are fixed strings, never built from shared text, HTML or URLs.
- **No JS network** (AGENTS.md 6, R-M09.2): no source under `src/`, `App.tsx`, `index.js` or `index.devcheck.js` references `fetch`, `XMLHttpRequest` or `WebSocket`. Task 1 adds the test that enforces the JS half. Avoid the bare words even in comments ("fetched" is fine; the guard matches whole words).
- **Positions are character offsets** `(paragraphIndex, charOffset)`, never sentence indices (AGENTS.md 10, R-M11). A sentence's array index is ephemeral and never persisted or exported for persistence.
- **Trimming is non-destructive** (AGENTS.md 12, R-M05): cuts are a separate `Set<number>` of paragraph indices; no function mutates or drops paragraph text.
- **Segmentation:** `Intl.Segmenter` (sentence granularity) when the runtime has it, a regex fallback otherwise; Hermes has none (SPIKE-03), so the fallback is the device path. Sentences over 400 characters are split at the last clause boundary before the cap (R-M08).
- **Extraction:** Readability over linkedom on Hermes (SPIKE-02). Headings and list items are their own paragraphs; tables, figures, code blocks, image captions and embedded media are dropped. Fewer than 3 paragraphs or under 500 characters is `extract-poor` (R-M04).
- **Dependencies:** OSI licences only (AGENTS.md 14); `node scripts/check-licenses.mjs` must pass after any `package.json` change.
- **Style:** no em-dash characters anywhere (code, comments, test strings, commits). Prettier formatting (`npx prettier --write <files>`), 2 spaces, single quotes. Comments explain why, not what.
- **TypeScript lib** has no DOM types and no `Intl.Segmenter` types (`@react-native/typescript-config`, lib `es2019`...`es2022.*`, verified 2026-10-02). Use the structural types given in each task; do not add `"dom"` to `tsconfig.json`.
- **Branch:** one feature branch for the whole phase, `feature/rea-<N>-text-pipeline`, one Linear issue, one PR at the end (Task 7).

## Review Focus

1. **Hermes and Node disagree on the fallback segmenter or on extraction** (Unicode case mapping, regex `\s`, string handling). Expected: identical fingerprints for every fixture on the phone and in Node. Pinned in Task 6 (devcheck parity, including a mixed-script text fixture).
2. **Non-Latin text**: CJK full stops with no following space, caseless scripts (Hebrew), accented capitals, emoji at the 400-character cut. Expected: CJK splits at `。！？`, a capital `É` or a Hebrew letter starts a new sentence, and no cut lands inside a surrogate pair. Pinned in Task 3.
3. **A shared URL wrapped in punctuation** (`(see https://x.com/a)`, a trailing period, quotes), a Wikipedia URL ending in `)`, the same URL twice. Expected: a link item with the URL as written minus wrapping punctuation; duplicates count once. Pinned in Task 2.
4. **A page with no article** (JavaScript-rendered shell, paywall stub), malformed HTML, an empty body. Expected: `poor: true` with whatever was found, never a thrown error. Pinned in Task 5.
5. **A reading position at an edge after a trim**: its paragraph cut, everything after it cut, every paragraph cut, an offset past the paragraph's end. Expected: the position moves to the start of the next kept paragraph, or `null` when nothing is left to read; lookup never returns a sentence in a cut paragraph. Pinned in Tasks 3 and 4.

## Verified before writing (2026-10-02)

Every code block in this plan was extracted and run in a throwaway worktree of `main` (`9cdabbc`
plus the Phase 0 merges), on this machine, before the plan was handed over. What ran:

- Jest, per file: networkGuard 2, intake 14, segment 20, trim 9, extract 12, devcheck.expected 2
  plus 1 skipped; with App, 60 passed and 1 skipped. `npm run typecheck` exit 0, `npm run lint`
  0 problems (after the two fixes below).
- Fixture downloads: weather.gov lightning 29,052 bytes, weather.gov flood 38,030, Gutenberg
  852,590 (HTTP 200 each). The generator wrote `synthetic-5mb` at 5,241,998 bytes and
  `segmentation-mixed` at 944 characters.
- Node expectations (`DEVCHECK_EXPECTED_OUT`, 5.1 s): gutenberg-1342 2240 paragraphs / 6200
  sentences; weather-gov-flood 21 / 52; weather-gov-lightning 13 / 34; synthetic-5mb 13952 /
  38366; segmentation-mixed 6 / 31. Live pages drift, so expect the weather.gov counts to differ
  if the fixtures are downloaded again.
- `./gradlew assembleRelease -PreadmeEntryFile=../../index.devcheck.js`: BUILD SUCCESSFUL, JS
  bundle 13,707,360 bytes, APK 66,668,041 bytes. Then the plain `assembleRelease`: bundle
  1,003,708 bytes with no `DEVCHECK` or `synthetic-5mb` string in it, so the product entry is
  untouched.
- Not run: anything on the phone (Task 6 Step 8 is the first device run).

Defects the run found, already fixed in this text: a literal U+2029 inside a regex literal (a
JavaScript line terminator, so the file did not parse; the escape `\u2029` is required); linkedom
gives an empty string no root and a fragment its own root, so `document.title` threw
(`parseDocument` wraps both); the tests needed Node types (`@types/node`, opted into per test
file); FNV-1a's bitwise operators tripped `no-bitwise` (scoped disable).

---

## File structure

| Path | Responsibility |
|---|---|
| `src/types.ts` | `Paragraph`, `ParagraphKind`, `Sentence`, `Position` |
| `src/intake/classify.ts` | R-M02: shared text to link, text or empty |
| `src/intake/paragraphs.ts` | R-M04 last bullet: shared text to paragraphs |
| `src/segment/segment.ts` | R-M08: paragraphs to sentences with offsets, Intl or fallback, 400 cap |
| `src/segment/locate.ts` | R-M11: which sentence a position resumes at |
| `src/trim/cuts.ts` | R-M05: cut operations and position remapping |
| `src/extract/extract.ts` | R-M04: HTML to title, site, byline, paragraphs, poor flag |
| `src/devcheck/fingerprint.ts` | Deterministic summary of a pipeline run, shared by the device and Node |
| `src/devcheck/DevCheck.tsx` | Root component of the devcheck bundle only |
| `index.devcheck.js` | Devcheck bundle entry (never the product entry) |
| `scripts/make-devcheck-fixtures.mjs` | Writes `src/devcheck/fixtures.generated.ts` (gitignored) |
| `scripts/fetch-page-fixtures.sh` | One-time download of the committed real-page fixtures |
| `scripts/devcheck.sh`, `scripts/devcheck-report.mjs` | Build, install and run the devcheck on the phone; compare with Node |
| `__tests__/fixtures/pages/*.html`, `SOURCES.md` | Real pages (public domain), committed |
| `__tests__/fixtures/text/segmentation-mixed.txt` | Hand-written mixed-script text |
| `__tests__/{networkGuard,intake,segment,trim,extract,devcheck.expected}.test.ts` | Tests |

---

### Task 1: Branch, dependencies, shared types, JS network guard

**Files:**
- Modify: `package.json`, `package-lock.json`, `babel.config.js`, `jest.config.js`, `.eslintrc.js`
- Create: `src/types.ts`, `__tests__/networkGuard.test.ts`

**Interfaces:**
- Produces: `src/types.ts`:
  - `type ParagraphKind = 'p' | 'heading' | 'li'`
  - `type Paragraph = { kind: ParagraphKind; text: string }` (its index in the item's array is its paragraph index)
  - `type Sentence = { paragraphIndex: number; start: number; end: number; text: string }` (`text === paragraphs[paragraphIndex].text.slice(start, end)`)
  - `type Position = { paragraphIndex: number; charOffset: number }`

- [ ] **Step 1: Create the Linear issue and the branch**

Create the issue with `save_issue` on the `linear-rea` server (team `Read-Me`, label `Feature`, state `In Progress`), title `Phase 1: text pipeline (intake, extract, segment, trim)`, description naming R-M02, R-M04, R-M05, R-M08, R-M11 and this plan's path. Read its identifier `REA-<N>` from the result.

```bash
git checkout main && git pull --ff-only
git checkout -b feature/rea-<N>-text-pipeline
git config branch.feature/rea-<N>-text-pipeline.base-branch main
```

- [ ] **Step 2: Add the dependencies and build configuration (SPIKE-02 build facts)**

```bash
npm install --save-exact @mozilla/readability@0.6.0 linkedom@0.18.13
npm install --save-dev --save-exact @babel/plugin-transform-export-namespace-from@7.29.7
# Node-only tests read fixtures with node:fs. The template's tsconfig has no Node types, and
# @types/node is only present transitively, so pin it; tests opt in with a triple-slash
# reference rather than tsconfig `types`, so app code is never typed as if it ran on Node.
npm install --save-dev --save-exact @types/node@24.19.1
```

`babel.config.js`:

```js
module.exports = {
  presets: ['module:@react-native/babel-preset'],
  // htmlparser2 (via linkedom) ships `export * as ns`; without this the release bundle fails
  // (SPIKE-02).
  plugins: ['@babel/plugin-transform-export-namespace-from'],
};
```

`jest.config.js`:

```js
module.exports = {
  preset: '@react-native/jest-preset',
  // linkedom's CJS build requires ESM-only packages (css-select and friends); transform them
  // (SPIKE-02).
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|linkedom|css-select|css-what|htmlparser2|domhandler|domutils|dom-serializer|domelementtype|entities|nth-check|boolbase)/)',
  ],
};
```

`.eslintrc.js` (also stops linting Gradle build output, REA-13):

```js
module.exports = {
  root: true,
  extends: '@react-native',
  ignorePatterns: ['android/**/build/**', '**/*.generated.ts'],
};
```

Run: `node scripts/check-licenses.mjs`
Expected: `... production packages, 1 recorded data exception(s)` and exit 0 (SPIKE-02 measured 471 packages with these added).

- [ ] **Step 3: Write `src/types.ts`**

```ts
// Shared shapes for the text pipeline. A paragraph's index in an item's array is its
// paragraph index; positions address text by (paragraphIndex, charOffset), never by a
// sentence index, because segmentation can change between runtime versions (R-M08, R-M11).
export type ParagraphKind = 'p' | 'heading' | 'li';

export type Paragraph = { kind: ParagraphKind; text: string };

/** `text` is exactly `paragraphs[paragraphIndex].text.slice(start, end)`. */
export type Sentence = {
  paragraphIndex: number;
  start: number;
  end: number;
  text: string;
};

export type Position = { paragraphIndex: number; charOffset: number };
```

- [ ] **Step 4: Write the network guard test**

`__tests__/networkGuard.test.ts`:

```ts
/// <reference types="node" />
// Node-only test: the app source never sees these types.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

// R-M09.2 and AGENTS.md 6, JS half: no app source may reference the JS network APIs. The
// Kotlin half (only Fetcher opens a connection) lands with Fetcher in Phase 2.
const ROOT = join(__dirname, '..');
const BANNED = /\b(fetch|XMLHttpRequest|WebSocket)\b/;
const SOURCE = /\.(ts|tsx|js|jsx)$/;

function sources(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return sources(path);
    // Generated fixture modules hold page HTML as data, not code.
    return SOURCE.test(name) && !name.endsWith('.generated.ts') ? [path] : [];
  });
}

test('the guard pattern catches each banned API', () => {
  expect(BANNED.test('await fetch(url)')).toBe(true);
  expect(BANNED.test('new XMLHttpRequest()')).toBe(true);
  expect(BANNED.test('new WebSocket(u)')).toBe(true);
  expect(BANNED.test('the body was fetched natively')).toBe(false);
});

test('no app source references fetch, XMLHttpRequest or WebSocket', () => {
  const files = [
    ...sources(join(ROOT, 'src')),
    ...['App.tsx', 'index.js', 'index.devcheck.js']
      .map(f => join(ROOT, f))
      .filter(existsSync),
  ];
  expect(files.length).toBeGreaterThan(0);
  const hits = files
    .filter(f => BANNED.test(readFileSync(f, 'utf8')))
    .map(f => f.slice(ROOT.length + 1));
  expect(hits).toEqual([]);
});
```

- [ ] **Step 5: Prove the guard fails on a violation**

```bash
printf "export const x = () => fetch('u');\n" > src/guardProbe.ts
npx jest __tests__/networkGuard.test.ts
```

Expected: FAIL, `hits` contains `src/guardProbe.ts`.

```bash
rm src/guardProbe.ts
npx jest __tests__/networkGuard.test.ts
```

Expected: PASS, 2 tests.

- [ ] **Step 6: Gates and commit**

```bash
npx prettier --write src/types.ts __tests__/networkGuard.test.ts babel.config.js jest.config.js .eslintrc.js
npm run typecheck && npm run lint && npm test
git add package.json package-lock.json babel.config.js jest.config.js .eslintrc.js src/types.ts __tests__/networkGuard.test.ts
git commit -m "feat(build): text pipeline dependencies, shared types, JS network guard

Refs REA-<N>"
```

Expected: typecheck and lint exit 0 (lint shows no `android/app/build` warnings), Jest 3 tests pass (App plus 2 guard tests).

---

### Task 2: `intake`: classify shared text, split shared text into paragraphs

**Files:**
- Create: `src/intake/classify.ts`, `src/intake/paragraphs.ts`
- Test: `__tests__/intake.test.ts`

**Interfaces:**
- Consumes: `Paragraph` from `src/types.ts`.
- Produces:
  - `type ShareClass = { kind: 'link'; url: string } | { kind: 'text'; text: string } | { kind: 'empty' }`
  - `classifyShare(shared: string): ShareClass`
  - `splitSharedText(text: string): Paragraph[]` (every paragraph has `kind: 'p'`)

Ruling recorded here: an empty or whitespace-only share is `{ kind: 'empty' }` (no item). R-M02 is silent on it, and an item with nothing to read would be a dead entry. Two occurrences of the same URL count as one URL; R-M02's "more than one URL" is about not guessing between different links.

- [ ] **Step 1: Write the failing tests**

`__tests__/intake.test.ts`:

```ts
import { classifyShare } from '../src/intake/classify';
import { splitSharedText } from '../src/intake/paragraphs';

describe('classifyShare (R-M02)', () => {
  test('a bare URL is a link item', () => {
    expect(classifyShare('https://example.com/a')).toEqual({
      kind: 'link',
      url: 'https://example.com/a',
    });
  });

  test('"Title https://..." is a link item, query kept as shared', () => {
    expect(
      classifyShare('Great read https://example.com/a?utm_source=x&id=2'),
    ).toEqual({ kind: 'link', url: 'https://example.com/a?utm_source=x&id=2' });
  });

  test('wrapping punctuation is not part of the URL', () => {
    expect(classifyShare('Read this: https://x.com/a.')).toEqual({
      kind: 'link',
      url: 'https://x.com/a',
    });
    expect(classifyShare('(see https://x.com/a)')).toEqual({
      kind: 'link',
      url: 'https://x.com/a',
    });
    expect(classifyShare('"https://x.com/a"')).toEqual({
      kind: 'link',
      url: 'https://x.com/a',
    });
  });

  test('a balanced closing parenthesis stays (Wikipedia style)', () => {
    expect(classifyShare('https://en.wikipedia.org/wiki/Foo_(bar)')).toEqual({
      kind: 'link',
      url: 'https://en.wikipedia.org/wiki/Foo_(bar)',
    });
  });

  test('the scheme is matched case-insensitively', () => {
    expect(classifyShare('HTTPS://EXAMPLE.COM/A')).toEqual({
      kind: 'link',
      url: 'HTTPS://EXAMPLE.COM/A',
    });
  });

  test('two different URLs make a text item (no guessing)', () => {
    const shared = 'compare https://a.com/x and https://b.com/y';
    expect(classifyShare(shared)).toEqual({ kind: 'text', text: shared });
  });

  test('the same URL twice is still one link', () => {
    expect(classifyShare('https://a.com/x https://a.com/x')).toEqual({
      kind: 'link',
      url: 'https://a.com/x',
    });
  });

  test('non-http schemes and scheme-less hosts are text', () => {
    expect(classifyShare('ftp://a.com/x')).toEqual({
      kind: 'text',
      text: 'ftp://a.com/x',
    });
    expect(classifyShare('see example.com/a')).toEqual({
      kind: 'text',
      text: 'see example.com/a',
    });
  });

  test('text with no URL is stored as shared', () => {
    const shared = 'Line one\n\nLine two';
    expect(classifyShare(shared)).toEqual({ kind: 'text', text: shared });
  });

  test('an empty or blank share is empty', () => {
    expect(classifyShare('')).toEqual({ kind: 'empty' });
    expect(classifyShare(' \n\t ')).toEqual({ kind: 'empty' });
  });
});

describe('splitSharedText (R-M04, text items)', () => {
  const texts = (s: string) => splitSharedText(s).map(p => p.text);

  test('blank lines separate paragraphs; single newlines join', () => {
    expect(texts('A\nb.\n\nC.')).toEqual(['A b.', 'C.']);
  });

  test('CRLF, blank lines holding spaces, and paragraph separators', () => {
    expect(texts('A\r\n\r\nB\n \t\nC\u2029D')).toEqual(['A', 'B', 'C', 'D']);
  });

  test('leading and trailing blank lines and runs of spaces collapse', () => {
    expect(texts('\n\n  one   two \n\n\n')).toEqual(['one two']);
  });

  test('every paragraph is kind p, and empty text gives none', () => {
    expect(splitSharedText('x\n\ny').every(p => p.kind === 'p')).toBe(true);
    expect(splitSharedText('   ')).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx jest __tests__/intake.test.ts`
Expected: FAIL with `Cannot find module '../src/intake/classify'`.

- [ ] **Step 3: Implement**

`src/intake/classify.ts`:

```ts
// R-M02: a share with exactly one distinct http(s) URL is a link item; anything else is a
// text item stored as shared. Never logs and never builds a message from the shared text.
export type ShareClass =
  | { kind: 'link'; url: string }
  | { kind: 'text'; text: string }
  | { kind: 'empty' };

const URL_PATTERN = /\bhttps?:\/\/[^\s<>"'`]+/gi;
const TRAILING = '.,;:!?\'"';
const PAIRS: Record<string, string> = { ')': '(', ']': '[', '}': '{' };

function count(s: string, ch: string): number {
  let n = 0;
  for (const c of s) if (c === ch) n++;
  return n;
}

/** Strips punctuation that wraps or ends a URL in prose, keeping balanced brackets. */
function trimUrl(raw: string): string {
  let url = raw;
  for (;;) {
    const last = url[url.length - 1];
    if (TRAILING.includes(last)) {
      url = url.slice(0, -1);
      continue;
    }
    const open = PAIRS[last];
    if (open !== undefined && count(url, open) < count(url, last)) {
      url = url.slice(0, -1);
      continue;
    }
    return url;
  }
}

export function classifyShare(shared: string): ShareClass {
  if (shared.trim() === '') return { kind: 'empty' };
  const urls = new Set((shared.match(URL_PATTERN) ?? []).map(trimUrl));
  if (urls.size === 1) return { kind: 'link', url: [...urls][0] };
  return { kind: 'text', text: shared };
}
```

`src/intake/paragraphs.ts`:

```ts
import type { Paragraph } from '../types';

// R-M04: shared text is split into paragraphs on blank lines; single newlines are joined.
export function splitSharedText(text: string): Paragraph[] {
  return text
    .replace(/\r\n?/g, '\n')
    .replace(/\u2029/g, '\n\n')
    .split(/\n[^\S\n]*\n/)
    .map(p => p.replace(/\s+/g, ' ').trim())
    .filter(p => p.length > 0)
    .map(p => ({ kind: 'p', text: p }));
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx jest __tests__/intake.test.ts`
Expected: PASS, 14 tests.

- [ ] **Step 5: Commit**

```bash
npx prettier --write src/intake __tests__/intake.test.ts
npm run typecheck && npm run lint
git add src/intake __tests__/intake.test.ts
git commit -m "feat(intake): classify shared text and split text items into paragraphs

Refs REA-<N>"
```

---

### Task 3: `segment`: sentences with offsets, fallback first, 400-character cap, resume lookup

**Files:**
- Create: `src/segment/segment.ts`, `src/segment/locate.ts`
- Test: `__tests__/segment.test.ts`

**Interfaces:**
- Consumes: `Paragraph`, `Sentence`, `Position` from `src/types.ts`.
- Produces:
  - `type SegmenterMode = 'auto' | 'intl' | 'fallback'`; `type SegmentOptions = { mode?: SegmenterMode; locale?: string }`
  - `MAX_SENTENCE = 400`
  - `hasIntlSegmenter(): boolean`
  - `segmentParagraph(text: string, paragraphIndex: number, options?: SegmentOptions): Sentence[]`
  - `segment(paragraphs: readonly Paragraph[], cuts?: ReadonlySet<number>, options?: SegmentOptions): Sentence[]` (cut paragraphs produce no sentences)
  - `sentenceIndexAt(sentences: readonly Sentence[], position: Position): number` (ephemeral array index, `-1` when nothing remains; never persist it)
  - `positionOf(sentence: Sentence): Position`

Rulings recorded here:
- The fallback splits after `. ! ? …` (plus closing quotes and brackets) followed by whitespace, **unless** the next character is a lowercase letter or `, ; : ) ] }`, or the period ends an abbreviation (list below), a single capital initial, or a dotted run like `e.g`, `U.S`, `p.m`. This splits before capitals, digits, quotes and caseless scripts, and merges rather than splits when unsure: a merged sentence still has exact offsets, a wrong split reads oddly.
- CJK `。！？` end a sentence with or without a following space.
- The 400 cap (R-M08 "the last clause boundary (`, ; :` or whitespace) before the cap"): the last `, ; :` followed by whitespace in the window, if it falls in the window's second half; otherwise the last whitespace; otherwise a hard cut at 400, moved back one code unit if it would split a surrogate pair. A clause cut near the start of the window would leave a fragment of a few words.

- [ ] **Step 1: Write the failing tests**

`__tests__/segment.test.ts`:

```ts
import {
  MAX_SENTENCE,
  hasIntlSegmenter,
  segment,
  segmentParagraph,
} from '../src/segment/segment';
import { positionOf, sentenceIndexAt } from '../src/segment/locate';
import type { Paragraph } from '../src/types';

const fallback = (text: string) =>
  segmentParagraph(text, 0, { mode: 'fallback' }).map(s => s.text);

const SAMPLE =
  'Dr. Smith went to Washington. He arrived at 3 p.m. on Jan. 5, 2026! ' +
  'Did it work? "Yes," she said. The U.S. economy grew 2.5% last year.';

describe('fallback segmenter (the Hermes path, SPIKE-03)', () => {
  test('abbreviations, decimals, quotes and questions', () => {
    expect(fallback(SAMPLE)).toEqual([
      'Dr. Smith went to Washington.',
      'He arrived at 3 p.m. on Jan. 5, 2026!',
      'Did it work?',
      '"Yes," she said.',
      'The U.S. economy grew 2.5% last year.',
    ]);
  });

  test('a closing quote stays with its sentence', () => {
    expect(fallback('He said "Stop." Then left.')).toEqual([
      'He said "Stop."',
      'Then left.',
    ]);
  });

  test('initials and number abbreviations do not split', () => {
    expect(fallback('J. R. R. Tolkien wrote it. See fig. 3 now.')).toEqual([
      'J. R. R. Tolkien wrote it.',
      'See fig. 3 now.',
    ]);
  });

  test('a lowercase continuation does not split', () => {
    expect(fallback('Wait... what happened? Nothing.')).toEqual([
      'Wait... what happened?',
      'Nothing.',
    ]);
  });

  test('accented capitals and caseless scripts start a sentence', () => {
    expect(fallback('Il est là. Élise arrive.')).toEqual([
      'Il est là.',
      'Élise arrive.',
    ]);
    expect(fallback('שלום לך. מה שלומך?')).toEqual(['שלום לך.', 'מה שלומך?']);
  });

  test('CJK full stops split with no following space', () => {
    expect(fallback('今日は晴れです。明日は雨です。')).toEqual([
      '今日は晴れです。',
      '明日は雨です。',
    ]);
  });

  test('every sentence is the exact slice its offsets name', () => {
    for (const s of segmentParagraph(SAMPLE, 4, { mode: 'fallback' })) {
      expect(s.paragraphIndex).toBe(4);
      expect(SAMPLE.slice(s.start, s.end)).toBe(s.text);
      expect(s.text).toBe(s.text.trim());
    }
  });
});

describe('400-character cap (R-M08)', () => {
  test('splits at whitespace, losing nothing', () => {
    const long = 'word '.repeat(120).trim() + '.';
    const out = fallback(long);
    expect(out.length).toBeGreaterThan(1);
    expect(out.every(t => t.length <= MAX_SENTENCE)).toBe(true);
    expect(out.join(' ')).toBe(long);
  });

  test('prefers a clause boundary in the second half of the window', () => {
    const long = 'a'.repeat(250) + ', ' + 'b '.repeat(150).trim() + '.';
    expect(fallback(long)[0]).toBe('a'.repeat(250) + ',');
  });

  test('hard-cuts text with no boundary at exactly 400', () => {
    expect(fallback('x'.repeat(900)).map(t => t.length)).toEqual([400, 400, 100]);
  });

  test('never cuts inside a surrogate pair', () => {
    const out = fallback('a' + '\u{1F600}'.repeat(300));
    for (const t of out) {
      expect(t.length).toBeLessThanOrEqual(MAX_SENTENCE);
      const last = t.charCodeAt(t.length - 1);
      expect(last >= 0xd800 && last <= 0xdbff).toBe(false);
    }
    expect(out.join('')).toBe('a' + '\u{1F600}'.repeat(300));
  });
});

describe('Intl path and mode selection', () => {
  test('Node has Intl.Segmenter, and the intl mode gives exact offsets', () => {
    expect(hasIntlSegmenter()).toBe(true);
    const out = segmentParagraph(SAMPLE, 0, { mode: 'intl' });
    expect(out.length).toBeGreaterThanOrEqual(4);
    for (const s of out) expect(SAMPLE.slice(s.start, s.end)).toBe(s.text);
  });

  test('without Intl.Segmenter, auto falls back and intl throws', () => {
    const intl = Intl as unknown as { Segmenter?: unknown };
    const saved = intl.Segmenter;
    delete intl.Segmenter;
    try {
      expect(hasIntlSegmenter()).toBe(false);
      expect(segmentParagraph(SAMPLE, 0, { mode: 'auto' })).toEqual(
        segmentParagraph(SAMPLE, 0, { mode: 'fallback' }),
      );
      expect(() => segmentParagraph(SAMPLE, 0, { mode: 'intl' })).toThrow(
        'Intl.Segmenter is not available',
      );
    } finally {
      intl.Segmenter = saved;
    }
  });
});

const PARAS: Paragraph[] = [
  { kind: 'heading', text: 'Title here.' },
  { kind: 'p', text: 'One. Two.' },
  { kind: 'p', text: 'Three. Four.' },
];

describe('segment over paragraphs with cuts', () => {
  test('cut paragraphs produce no sentences; indices are kept', () => {
    const out = segment(PARAS, new Set([1]), { mode: 'fallback' });
    expect(out.map(s => [s.paragraphIndex, s.text])).toEqual([
      [0, 'Title here.'],
      [2, 'Three.'],
      [2, 'Four.'],
    ]);
  });
});

describe('sentenceIndexAt and positionOf (R-M11)', () => {
  const sentences = segment(PARAS, new Set(), { mode: 'fallback' });
  const at = (paragraphIndex: number, charOffset: number) =>
    sentenceIndexAt(sentences, { paragraphIndex, charOffset });

  test('an offset inside a sentence resumes at that sentence', () => {
    expect(sentences[at(1, 6)].text).toBe('Two.');
    expect(sentences[at(1, 0)].text).toBe('One.');
  });

  test('an offset on the space between sentences resumes at the next', () => {
    expect(sentences[at(1, 4)].text).toBe('Two.');
  });

  test('an offset past the end of a paragraph resumes in the next one', () => {
    expect(sentences[at(1, 999)].text).toBe('Three.');
  });

  test('a position in a cut paragraph resumes at the next kept one', () => {
    const kept = segment(PARAS, new Set([1]), { mode: 'fallback' });
    const i = sentenceIndexAt(kept, { paragraphIndex: 1, charOffset: 2 });
    expect(kept[i].text).toBe('Three.');
  });

  test('past the last sentence there is nothing to resume', () => {
    expect(at(2, 999)).toBe(-1);
    expect(sentenceIndexAt([], { paragraphIndex: 0, charOffset: 0 })).toBe(-1);
  });

  test('positionOf is the sentence start', () => {
    expect(positionOf(sentences[at(2, 8)])).toEqual({
      paragraphIndex: 2,
      charOffset: 7,
    });
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx jest __tests__/segment.test.ts`
Expected: FAIL with `Cannot find module '../src/segment/segment'`.

- [ ] **Step 3: Implement `src/segment/segment.ts`**

```ts
// R-M08: paragraphs to sentences with exact character offsets. Intl.Segmenter when the
// runtime has it; Hermes does not (SPIKE-03), so the fallback below is the device path and
// Task 6's devcheck proves Hermes and Node agree on it.
import type { Paragraph, Sentence } from '../types';

export type SegmenterMode = 'auto' | 'intl' | 'fallback';
export type SegmentOptions = { mode?: SegmenterMode; locale?: string };

export const MAX_SENTENCE = 400;

type Span = { start: number; end: number };
type IntlSegmenterCtor = new (
  locale: string,
  options: { granularity: 'sentence' },
) => { segment(text: string): Iterable<{ segment: string; index: number }> };

function intlSegmenter(): IntlSegmenterCtor | undefined {
  const ctor = (Intl as unknown as { Segmenter?: unknown }).Segmenter;
  return typeof ctor === 'function' ? (ctor as IntlSegmenterCtor) : undefined;
}

export function hasIntlSegmenter(): boolean {
  return intlSegmenter() !== undefined;
}

const isSpace = (c: string) => /\s/.test(c);

function pushTrimmed(text: string, start: number, end: number, out: Span[]): void {
  while (start < end && isSpace(text[start])) start++;
  while (end > start && isSpace(text[end - 1])) end--;
  if (end > start) out.push({ start, end });
}

function intlSpans(text: string, locale: string, Ctor: IntlSegmenterCtor): Span[] {
  const spans: Span[] = [];
  for (const s of new Ctor(locale, { granularity: 'sentence' }).segment(text)) {
    pushTrimmed(text, s.index, s.index + s.segment.length, spans);
  }
  return spans;
}

const TERMINAL = '.!?…';
const CJK_TERMINAL = '。！？';
const CLOSERS = '"\')]}»”’」』';
const NO_SPLIT_BEFORE = ',;:)]}';
// Always an abbreviation before a following word.
const ABBREVIATIONS = new Set([
  'mr', 'mrs', 'ms', 'dr', 'prof', 'sr', 'jr', 'st', 'vs', 'etc', 'approx', 'dept',
  'inc', 'ltd', 'co', 'corp', 'mt', 'gen', 'gov', 'sen', 'rep', 'jan', 'feb', 'mar',
  'apr', 'jun', 'jul', 'aug', 'sep', 'sept', 'oct', 'nov', 'dec',
]);
// Abbreviations only before a number ("No. 5", "fig. 3"); "I said no. Then" still splits.
const NUMBER_ABBREVIATIONS = new Set(['no', 'fig', 'vol', 'ch', 'p', 'pp', 'art', 'sec']);

const isLower = (c: string) => c !== c.toUpperCase() && c === c.toLowerCase();

function isAbbreviation(text: string, dot: number, next: string): boolean {
  let s = dot;
  while (s > 0 && /[A-Za-z.]/.test(text[s - 1])) s--;
  const token = text.slice(s, dot);
  if (token === '') return false;
  if (/^[A-Z]$/.test(token)) return true;
  if (/^([A-Za-z]\.)+[A-Za-z]$/.test(token)) return true;
  const lower = token.toLowerCase();
  if (ABBREVIATIONS.has(lower)) return true;
  return NUMBER_ABBREVIATIONS.has(lower) && /[0-9]/.test(next);
}

function fallbackSpans(text: string): Span[] {
  const spans: Span[] = [];
  let start = 0;
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    const cjk = CJK_TERMINAL.includes(c);
    if (!cjk && !TERMINAL.includes(c)) {
      i++;
      continue;
    }
    let j = i + 1;
    while (
      j < text.length &&
      (TERMINAL.includes(text[j]) ||
        CJK_TERMINAL.includes(text[j]) ||
        CLOSERS.includes(text[j]))
    ) {
      j++;
    }
    let k = j;
    while (k < text.length && isSpace(text[k])) k++;
    if (k >= text.length) break;
    const next = text[k];
    const boundary = cjk
      ? true
      : k > j &&
        !isLower(next) &&
        !NO_SPLIT_BEFORE.includes(next) &&
        !(c === '.' && isAbbreviation(text, i, next));
    if (boundary) {
      pushTrimmed(text, start, j, spans);
      start = k;
      i = k;
    } else {
      i = j;
    }
  }
  pushTrimmed(text, start, text.length, spans);
  return spans;
}

function lastClauseCut(text: string, start: number, windowEnd: number): number {
  const half = start + MAX_SENTENCE / 2;
  for (let p = windowEnd - 1; p >= half; p--) {
    if (',;:'.includes(text[p]) && p + 1 < text.length && isSpace(text[p + 1])) {
      return p + 1;
    }
  }
  return -1;
}

function lastSpaceCut(text: string, start: number, windowEnd: number): number {
  for (let p = Math.min(windowEnd, text.length - 1); p > start; p--) {
    if (isSpace(text[p])) return p;
  }
  return -1;
}

function capSpan(text: string, span: Span, out: Span[]): void {
  let start = span.start;
  const end = span.end;
  while (end - start > MAX_SENTENCE) {
    const windowEnd = start + MAX_SENTENCE;
    let cut = lastClauseCut(text, start, windowEnd);
    if (cut <= start) cut = lastSpaceCut(text, start, windowEnd);
    if (cut <= start) {
      cut = windowEnd;
      const unit = text.charCodeAt(cut - 1);
      if (unit >= 0xd800 && unit <= 0xdbff) cut--;
    }
    pushTrimmed(text, start, cut, out);
    start = cut;
    while (start < end && isSpace(text[start])) start++;
  }
  pushTrimmed(text, start, end, out);
}

export function segmentParagraph(
  text: string,
  paragraphIndex: number,
  options: SegmentOptions = {},
): Sentence[] {
  const mode = options.mode ?? 'auto';
  const Ctor = mode === 'fallback' ? undefined : intlSegmenter();
  if (mode === 'intl' && Ctor === undefined) {
    throw new Error('Intl.Segmenter is not available');
  }
  const spans = Ctor
    ? intlSpans(text, options.locale ?? 'en', Ctor)
    : fallbackSpans(text);
  const capped: Span[] = [];
  for (const s of spans) capSpan(text, s, capped);
  return capped.map(s => ({
    paragraphIndex,
    start: s.start,
    end: s.end,
    text: text.slice(s.start, s.end),
  }));
}

export function segment(
  paragraphs: readonly Paragraph[],
  cuts: ReadonlySet<number> = new Set(),
  options: SegmentOptions = {},
): Sentence[] {
  const out: Sentence[] = [];
  paragraphs.forEach((p, i) => {
    if (cuts.has(i)) return;
    for (const s of segmentParagraph(p.text, i, options)) out.push(s);
  });
  return out;
}
```

- [ ] **Step 4: Implement `src/segment/locate.ts`**

```ts
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
  return { paragraphIndex: sentence.paragraphIndex, charOffset: sentence.start };
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npx jest __tests__/segment.test.ts`
Expected: PASS, 20 tests. If a fallback expectation fails, fix the segmenter, not the expectation: each one is a reading the user hears.

- [ ] **Step 6: Commit**

```bash
npx prettier --write src/segment __tests__/segment.test.ts
npm run typecheck && npm run lint
git add src/segment __tests__/segment.test.ts
git commit -m "feat(segment): sentences with offsets, regex fallback, 400-char cap, resume lookup

Refs REA-<N>"
```

---

### Task 4: `trim`: non-destructive cuts and position remapping

**Files:**
- Create: `src/trim/cuts.ts`
- Test: `__tests__/trim.test.ts`

**Interfaces:**
- Consumes: `Position` from `src/types.ts`.
- Produces (every function returns a new set and never mutates its input):
  - `toggleCut(cuts: ReadonlySet<number>, index: number, count: number): Set<number>`
  - `cutAfter(cuts: ReadonlySet<number>, index: number, count: number): Set<number>`
  - `startHere(cuts: ReadonlySet<number>, index: number, count: number): Set<number>`
  - `keptIndices(cuts: ReadonlySet<number>, count: number): number[]`
  - `remapPosition(position: Position, cuts: ReadonlySet<number>, count: number): Position | null`

Rulings recorded here: "Cut everything after this" and "Start here" both leave the anchor paragraph kept (restoring it if it was cut); paragraphs on the other side keep their own state. `remapPosition` returns `null` when no kept paragraph remains at or after the position; Phase 4 decides what the Reader shows then (R-M05 names only "the next kept paragraph"). An index outside `0..count-1` throws `RangeError('paragraph index out of range')`, a fixed message.

- [ ] **Step 1: Write the failing tests**

`__tests__/trim.test.ts`:

```ts
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
    expect(() => toggleCut(new Set(), 5, 5)).toThrow('paragraph index out of range');
    expect(() => cutAfter(new Set(), -1, 5)).toThrow('paragraph index out of range');
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
    expect(remapPosition(pos(2, 17), cutAfter(new Set([2]), 1, 5), 5)).toBeNull();
    expect(remapPosition(pos(0, 0), new Set([0, 1, 2]), 3)).toBeNull();
  });

  test('a position beyond the item gives null', () => {
    expect(remapPosition(pos(9, 0), new Set(), 3)).toBeNull();
  });
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx jest __tests__/trim.test.ts`
Expected: FAIL with `Cannot find module '../src/trim/cuts'`.

- [ ] **Step 3: Implement `src/trim/cuts.ts`**

```ts
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

export function keptIndices(cuts: ReadonlySet<number>, count: number): number[] {
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npx jest __tests__/trim.test.ts`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
npx prettier --write src/trim __tests__/trim.test.ts
npm run typecheck && npm run lint
git add src/trim __tests__/trim.test.ts
git commit -m "feat(trim): non-destructive cuts and position remapping

Refs REA-<N>"
```

---

### Task 5: `extract`: Readability over linkedom, against saved real pages

**Files:**
- Create: `scripts/fetch-page-fixtures.sh`, `__tests__/fixtures/pages/{weather-gov-lightning,weather-gov-flood,gutenberg-1342}.html`, `__tests__/fixtures/pages/SOURCES.md`, `src/extract/extract.ts`
- Test: `__tests__/extract.test.ts`

**Interfaces:**
- Consumes: `Paragraph`, `ParagraphKind` from `src/types.ts`.
- Produces:
  - `type Extracted = { title: string; site?: string; byline?: string; paragraphs: Paragraph[]; poor: boolean }`
  - `POOR_MIN_PARAGRAPHS = 3`, `POOR_MIN_CHARS = 500`
  - `extractArticle(html: string, url?: string): Extracted` (never throws on page content; `site` falls back to the URL's host without `www.`)

R-M14 requires extraction tests against saved real pages, not pages downloaded during tests. The fixtures are US government works (weather.gov, National Weather Service) and a Project Gutenberg public-domain book, so they can live in the repository. They are test data only and never enter the APK.

- [ ] **Step 1: Download and commit the fixtures**

`scripts/fetch-page-fixtures.sh`:

```bash
#!/usr/bin/env bash
# One-time download of the real-page extraction fixtures (dev machine only; tests never
# download). Re-running replaces them; commit the result and update SOURCES.md's date.
set -euo pipefail
cd "$(dirname "$0")/.."
D=__tests__/fixtures/pages
mkdir -p "$D"
UA="Mozilla/5.0 (X11; Linux x86_64) ReadMe-fixtures"
curl -sSfL -A "$UA" https://www.weather.gov/safety/lightning-science-overview -o "$D/weather-gov-lightning.html"
curl -sSfL -A "$UA" https://www.weather.gov/safety/flood-turn-around-dont-drown -o "$D/weather-gov-flood.html"
curl -sSfL -A "$UA" https://www.gutenberg.org/cache/epub/1342/pg1342-images.html -o "$D/gutenberg-1342.html"
wc -c "$D"/*.html
```

```bash
chmod +x scripts/fetch-page-fixtures.sh && scripts/fetch-page-fixtures.sh
```

Expected: three files. On 2026-10-02 the sizes were 29,052, 38,030 and 852,590 bytes; record today's.

`__tests__/fixtures/pages/SOURCES.md`:

```markdown
# Extraction fixtures

Saved real pages for `__tests__/extract.test.ts` (R-M14: extraction is tested against saved
pages, never downloaded during tests). Test data only; none of it is bundled into the app.

| File | Source | Retrieved | Status |
|---|---|---|---|
| `weather-gov-lightning.html` | https://www.weather.gov/safety/lightning-science-overview | <date> | US government work (National Weather Service), public domain in the US |
| `weather-gov-flood.html` | https://www.weather.gov/safety/flood-turn-around-dont-drown | <date> | US government work (National Weather Service), public domain in the US |
| `gutenberg-1342.html` | https://www.gutenberg.org/cache/epub/1342/pg1342-images.html | <date> | Jane Austen, *Pride and Prejudice*: public domain; redistributed with the Project Gutenberg header and licence it carries |

Refresh with `scripts/fetch-page-fixtures.sh`.
```

Replace each `<date>` with the actual download date.

- [ ] **Step 2: Write the failing tests**

`__tests__/extract.test.ts`:

```ts
/// <reference types="node" />
// Node-only test: the app source never sees these types.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { extractArticle } from '../src/extract/extract';

const page = (name: string) =>
  readFileSync(join(__dirname, 'fixtures/pages', name), 'utf8');

// Long enough for Readability to accept as an article (its default charThreshold is 500).
const PROSE =
  'The river rose for three days and the town watched it climb the stone wall. ' +
  'Nobody had seen water that high since the old mill burned, and the ferry stopped. ';

const STRUCTURED = `<!doctype html><html><head><title>River Notes</title></head><body>
<nav><a href="/">Home</a> NAVLINK</nav>
<article>
  <h1>River Notes</h1>
  <p>${PROSE}</p>
  <h2>Second section</h2>
  <p>${PROSE} Inline code like <code>npm test</code> stays.</p>
  <ul><li>First item<ul><li>Inner item</li></ul></li><li>Second item</li></ul>
  <table><tr><th>TABLEHEAD</th></tr><tr><td>TABLECELL</td></tr></table>
  <figure><img src="a.png" alt="ALTTEXT"><figcaption>CAPTIONTEXT</figcaption></figure>
  <pre><code>CODEBLOCK();</code></pre>
  <iframe src="https://example.com/embed">IFRAMETEXT</iframe>
  <p>${PROSE}</p>
</article></body></html>`;

describe('extractArticle structure (R-M04)', () => {
  const ex = extractArticle(STRUCTURED, 'https://www.example.com/notes');
  const texts = ex.paragraphs.map(p => p.text);
  const all = texts.join('\n');

  test('title, and the site falls back to the host without www', () => {
    expect(ex.title).toBe('River Notes');
    expect(ex.site).toBe('example.com');
  });

  test('headings and list items are their own paragraphs, in order', () => {
    const heading = ex.paragraphs.findIndex(
      p => p.kind === 'heading' && p.text === 'Second section',
    );
    const first = ex.paragraphs.findIndex(p => p.kind === 'li' && p.text === 'First item');
    expect(heading).toBeGreaterThanOrEqual(0);
    expect(first).toBeGreaterThan(heading);
    expect(ex.paragraphs).toContainEqual({ kind: 'li', text: 'Inner item' });
    expect(ex.paragraphs).toContainEqual({ kind: 'li', text: 'Second item' });
  });

  test('inline code is kept as text', () => {
    expect(all).toContain('Inline code like npm test stays.');
  });

  test('tables, figures, captions, code blocks, embeds and nav are dropped', () => {
    for (const gone of [
      'TABLEHEAD', 'TABLECELL', 'ALTTEXT', 'CAPTIONTEXT', 'CODEBLOCK', 'IFRAMETEXT', 'NAVLINK',
    ]) {
      expect(all).not.toContain(gone);
    }
  });

  test('paragraph text is whitespace-collapsed and never empty', () => {
    for (const t of texts) {
      expect(t).toBe(t.replace(/\s+/g, ' ').trim());
      expect(t.length).toBeGreaterThan(0);
    }
  });

  test('a substantial article is not poor', () => {
    expect(ex.poor).toBe(false);
  });
});

describe('pages with no article never throw (Review Focus 4)', () => {
  test('a JavaScript-rendered shell is poor', () => {
    const ex = extractArticle(
      '<!doctype html><html><head><title>App</title></head><body><div id="root"></div>' +
        '<script>render()</script></body></html>',
    );
    expect(ex.poor).toBe(true);
    expect(ex.title).toBe('App');
  });

  test('a paywall stub is poor but keeps what it found', () => {
    const ex = extractArticle(
      '<html><head><title>Story</title></head><body><article><p>The first line of the ' +
        'story.</p><p>Subscribe to read more.</p></article></body></html>',
    );
    expect(ex.poor).toBe(true);
    expect(ex.paragraphs.map(p => p.text).join(' ')).toContain('first line');
  });

  test('malformed HTML and an empty string do not throw', () => {
    expect(() => extractArticle('<p>Unclosed <b>bold <i>both</p><div>')).not.toThrow();
    const empty = extractArticle('');
    expect(empty.poor).toBe(true);
    expect(empty.title).toBe('');
    expect(empty.paragraphs).toEqual([]);
  });
});

describe('saved real pages (R-M14)', () => {
  test.each([
    ['weather-gov-lightning.html', /lightning/i, 5],
    ['weather-gov-flood.html', /flood/i, 5],
    ['gutenberg-1342.html', /Elizabeth/, 1000],
  ])('%s extracts as a readable article', (file, word, minParagraphs) => {
    const ex = extractArticle(page(file));
    expect(ex.poor).toBe(false);
    expect(ex.title.length).toBeGreaterThan(0);
    expect(ex.paragraphs.length).toBeGreaterThanOrEqual(minParagraphs);
    expect(ex.paragraphs.some(p => word.test(p.text))).toBe(true);
    for (const p of ex.paragraphs) {
      expect(['p', 'heading', 'li']).toContain(p.kind);
      expect(p.text.length).toBeGreaterThan(0);
    }
  });
});
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `npx jest __tests__/extract.test.ts`
Expected: FAIL with `Cannot find module '../src/extract/extract'`.

- [ ] **Step 4: Implement `src/extract/extract.ts`**

```ts
// R-M04: HTML to title, site, byline and paragraphs, on-device: Readability over linkedom
// (SPIKE-02). Returns structure only; the caller discards the HTML. Never logs.
import { Readability } from '@mozilla/readability';
import { parseHTML } from 'linkedom';
import type { Paragraph, ParagraphKind } from '../types';

export type Extracted = {
  title: string;
  site?: string;
  byline?: string;
  paragraphs: Paragraph[];
  poor: boolean;
};

export const POOR_MIN_PARAGRAPHS = 3;
export const POOR_MIN_CHARS = 500;

// The TS lib here has no DOM types; this is the part of linkedom's node shape we use.
type DomNode = {
  nodeType: number;
  nodeName: string;
  textContent: string | null;
  childNodes: ArrayLike<DomNode>;
};
type ReadabilityDoc = ConstructorParameters<typeof Readability>[0];

const ELEMENT_NODE = 1;
const TEXT_NODE = 3;

// R-M04 drops tables, figures, code blocks, captions and embedded media; the rest is page
// chrome that should never be read aloud when Readability finds no article.
const DROP = new Set([
  'TABLE', 'FIGURE', 'FIGCAPTION', 'PRE', 'PICTURE', 'IMG', 'VIDEO', 'AUDIO', 'IFRAME',
  'OBJECT', 'EMBED', 'SVG', 'MATH', 'CANVAS', 'NOSCRIPT', 'SCRIPT', 'STYLE', 'TEMPLATE',
  'FORM', 'BUTTON', 'SELECT', 'TEXTAREA', 'INPUT', 'NAV', 'ASIDE', 'HEADER', 'FOOTER',
]);
const BLOCK = new Set([
  'P', 'DIV', 'SECTION', 'ARTICLE', 'MAIN', 'BLOCKQUOTE', 'UL', 'OL', 'LI', 'DL', 'DT',
  'DD', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'HR', 'ADDRESS', 'DETAILS', 'SUMMARY', 'BODY',
]);

function kindFor(tag: string): ParagraphKind {
  if (/^H[1-6]$/.test(tag)) return 'heading';
  return tag === 'LI' ? 'li' : 'p';
}

/** Emits one paragraph per run of inline text, each block element starting a new run. */
function collect(root: DomNode, kind: ParagraphKind, out: Paragraph[]): void {
  let buf = '';
  const flush = () => {
    const text = buf.replace(/\s+/g, ' ').trim();
    if (text) out.push({ kind, text });
    buf = '';
  };
  const visit = (node: DomNode): void => {
    if (node.nodeType === TEXT_NODE) {
      buf += node.textContent ?? '';
      return;
    }
    if (node.nodeType !== ELEMENT_NODE) return;
    const tag = node.nodeName.toUpperCase();
    if (DROP.has(tag)) return;
    if (tag === 'BR') {
      buf += ' ';
      return;
    }
    if (BLOCK.has(tag)) {
      flush();
      collect(node, kindFor(tag), out);
      return;
    }
    Array.from(node.childNodes).forEach(visit);
  };
  Array.from(root.childNodes).forEach(visit);
  flush();
}

const clean = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

function hostOf(url: string | undefined): string | undefined {
  const m = /^https?:\/\/(?:[^/?#@]*@)?([^/?#:]+)/i.exec(url ?? '');
  return m ? m[1].toLowerCase().replace(/^www\./, '') : undefined;
}

type Article = ReturnType<Readability['parse']>;

/**
 * linkedom gives an empty string no root element, and a fragment ("<p>...") the fragment's
 * own root, and its `title` and `body` getters then throw or read nothing; wrap both into a
 * full document.
 */
function parseDocument(html: string) {
  const { document } = parseHTML(html);
  if (document.documentElement?.nodeName.toUpperCase() === 'HTML') return document;
  return parseHTML(`<!doctype html><html><head></head><body>${html}</body></html>`).document;
}

export function extractArticle(html: string, url?: string): Extracted {
  const document = parseDocument(html);
  const pageTitle = clean(document.title);
  let article: Article = null;
  try {
    article = new Readability(document as unknown as ReadabilityDoc).parse();
  } catch {
    article = null;
  }

  const paragraphs: Paragraph[] = [];
  if (article?.content) {
    const { document: content } = parseHTML(
      `<!doctype html><html><body>${article.content}</body></html>`,
    );
    collect(content.body as unknown as DomNode, 'p', paragraphs);
  } else {
    // Readability mutates the document it reads, so the fallback reads a fresh parse.
    const fresh = parseDocument(html);
    if (fresh.body) collect(fresh.body as unknown as DomNode, 'p', paragraphs);
  }

  const chars = paragraphs.reduce((n, p) => n + p.text.length, 0);
  return {
    title: clean(article?.title) || pageTitle,
    site: clean(article?.siteName) || hostOf(url),
    byline: clean(article?.byline) || undefined,
    paragraphs,
    poor: paragraphs.length < POOR_MIN_PARAGRAPHS || chars < POOR_MIN_CHARS,
  };
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npx jest __tests__/extract.test.ts`
Expected: PASS, 12 tests. If a real-page test fails, print that page's `ex.paragraphs.length`, `ex.title` and the first five paragraph texts, and decide from the output whether extraction is wrong (fix the code) or the page changed shape (record it in the ledger, adjust the threshold, and say so in the PR). Never loosen a structural test (dropped elements, kinds, order).

- [ ] **Step 6: Commit**

```bash
npx prettier --write src/extract __tests__/extract.test.ts
npm run typecheck && npm run lint && npm test && node scripts/check-licenses.mjs
git add scripts/fetch-page-fixtures.sh __tests__/fixtures/pages src/extract __tests__/extract.test.ts
git commit -m "feat(extract): Readability over linkedom to paragraphs, tested on saved real pages

Refs REA-<N>"
```

---

### Task 6: Devcheck: the real pipeline on Hermes, compared with Node (F19, SPIKE-02 re-measure)

**Files:**
- Modify: `android/app/build.gradle` (the `react { }` block), `package.json` (scripts), `.gitignore`
- Create: `__tests__/fixtures/text/segmentation-mixed.txt`, `scripts/make-devcheck-fixtures.mjs`, `src/devcheck/fingerprint.ts`, `src/devcheck/DevCheck.tsx`, `index.devcheck.js`, `__tests__/devcheck.expected.test.ts`, `scripts/devcheck.sh`, `scripts/devcheck-report.mjs`

**Interfaces:**
- Consumes: `extractArticle` (Task 5), `segment`, `SegmenterMode` (Task 3), `splitSharedText` (Task 2).
- Produces:
  - `type Fingerprint = { name: string; bytes?: number; extractMs: number; segmentMs: number; paragraphs: number; chars: number; sentences: number; poor: boolean; hash: string }`
  - `fingerprintPage(name: string, html: string, bytes: number, mode: SegmenterMode): Fingerprint`
  - `fingerprintText(name: string, text: string, mode: SegmenterMode): Fingerprint`
  - `fnv1a(s: string, seed?: number): number`
  - `src/devcheck/fixtures.generated.ts` (gitignored): `PAGES: { name: string; bytes: number; html: string }[]`, `TEXTS: { name: string; text: string }[]`
  - `npm run device:devcheck [runs]`: exit 0 when every fixture's fingerprint matches Node in every run and the F17 lines hold; 1 on a mismatch, a timeout or an F17 miss; 2 if the app dies; 3 no device slot; 5 phone locked; 6 uncommitted changes.

Why a separate bundle: the fixtures are about 6 MB of HTML and must never ship in the product APK, and the product needs no hidden "run self-check" path. The devcheck build replaces only the JS entry (`-PreadmeEntryFile`), so the native app is the product's. After a devcheck run the product APK and its stamp are deleted, so `device:install`/`device:smoke` refuse until `npm run build:release` runs again.

- [ ] **Step 1: Write the mixed-script text fixture**

`__tests__/fixtures/text/segmentation-mixed.txt` (hand-written for this repo; exercises Review Focus 1 and 2):

```text
Dr. Smith went to Washington. He arrived at 3 p.m. on Jan. 5, 2026! Did it work? "Yes," she said. The U.S. economy grew 2.5% last year.

J. R. R. Tolkien wrote it. See fig. 3 now. Wait... what happened? Nothing. He said "Stop." Then left.

Il est là. Élise arrive. Ça va? Øystein svarte. Straße und Äpfel. Ünal kam spät.

שלום לך. מה שלומך? Привет. Как дела? Ελλάδα. Είναι ωραία.

今日は晴れです。明日は雨です。本当ですか？はい！

Emoji 😀 at the start. 🎉 Party time. A very long run-on sentence follows, and it keeps going with clauses, commas, and more words than any reader would want in one breath, so that the four hundred character cap has to cut it somewhere sensible, which means at the last comma in the second half of the window, or failing that at the last space, and it continues for a while longer still, adding words, adding commas, adding the kind of filler that a careless writer produces when they never reach for a full stop, until at last it ends.
```

- [ ] **Step 2: Write the fixture generator and wire the scripts**

`scripts/make-devcheck-fixtures.mjs`:

```js
// Writes src/devcheck/fixtures.generated.ts (gitignored) from the committed fixtures, plus a
// synthetic page of real prose at R-M03's 5 MB cap (the Gutenberg body repeated inside one
// <article>, cut on a tag boundary), the size SPIKE-02's F17 line names.
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const CAP_BYTES = 5 * 1024 * 1024;
const pagesDir = '__tests__/fixtures/pages';
const textsDir = '__tests__/fixtures/text';

const pages = readdirSync(pagesDir)
  .filter(f => f.endsWith('.html'))
  .sort()
  .map(f => ({ name: f.replace(/\.html$/, ''), html: readFileSync(join(pagesDir, f), 'utf8') }));

const book = pages.find(p => p.name === 'gutenberg-1342');
if (!book) throw new Error('missing gutenberg-1342.html; run scripts/fetch-page-fixtures.sh');
const body = book.html.slice(book.html.indexOf('<body'), book.html.lastIndexOf('</body>'));
const inner = body.slice(body.indexOf('>') + 1);
const head = '<!doctype html><html><head><title>Synthetic 5 MB</title></head><body><article>';
const tail = '</article></body></html>';
const room = CAP_BYTES - Buffer.byteLength(head + tail);
let big = '';
while (Buffer.byteLength(big) < room) big += inner;
big = Buffer.from(big).subarray(0, room).toString('utf8');
big = big.slice(0, big.lastIndexOf('<'));
pages.push({ name: 'synthetic-5mb', html: head + big + tail });

const texts = readdirSync(textsDir)
  .filter(f => f.endsWith('.txt'))
  .sort()
  .map(f => ({ name: f.replace(/\.txt$/, ''), text: readFileSync(join(textsDir, f), 'utf8') }));

const withBytes = pages.map(p => ({ name: p.name, bytes: Buffer.byteLength(p.html), html: p.html }));
writeFileSync(
  'src/devcheck/fixtures.generated.ts',
  '// Generated by scripts/make-devcheck-fixtures.mjs. Do not edit; gitignored.\n' +
    'export const PAGES: { name: string; bytes: number; html: string }[] = ' +
    JSON.stringify(withBytes) +
    ';\nexport const TEXTS: { name: string; text: string }[] = ' +
    JSON.stringify(texts) +
    ';\n',
);
for (const p of withBytes) console.log(p.name, p.bytes, 'bytes');
for (const t of texts) console.log(t.name, t.text.length, 'chars');
```

In `package.json` `"scripts"`, add (the hooks keep typecheck and Jest working on a fresh clone, where the generated module does not exist yet):

```json
    "devcheck:fixtures": "node scripts/make-devcheck-fixtures.mjs",
    "postinstall": "node scripts/make-devcheck-fixtures.mjs",
    "pretest": "node scripts/make-devcheck-fixtures.mjs",
    "pretypecheck": "node scripts/make-devcheck-fixtures.mjs",
    "device:devcheck": "scripts/devcheck.sh",
```

```bash
mkdir -p src/devcheck
printf '\n# Phase 1 devcheck generated fixtures\nsrc/devcheck/fixtures.generated.ts\n' >> .gitignore
npm run devcheck:fixtures
```

Expected: five lines: the three pages, `synthetic-5mb` at no more than 5,242,880 bytes, and `segmentation-mixed` with its character count. `git status --short` does not list the generated file.

- [ ] **Step 3: Write the failing determinism test**

`__tests__/devcheck.expected.test.ts`:

```ts
/// <reference types="node" />
// Node-only test: the app source never sees these types.
import { writeFileSync } from 'node:fs';
import { fingerprintPage, fingerprintText, fnv1a } from '../src/devcheck/fingerprint';

test('fnv1a is the 32-bit FNV-1a of UTF-16 code units', () => {
  expect(fnv1a('')).toBe(0x811c9dc5);
  expect(fnv1a('a')).toBe(0xe40c292c);
});

test('a fingerprint is deterministic and covers structure', () => {
  const html =
    '<html><head><title>T</title></head><body><article><p>One. Two.</p></article></body></html>';
  const a = fingerprintPage('x', html, html.length, 'fallback');
  const b = fingerprintPage('x', html, html.length, 'fallback');
  expect(a.hash).toBe(b.hash);
  expect(a.sentences).toBe(2);
  const t = fingerprintText('t', 'A b.\n\nC d.', 'fallback');
  expect(t.paragraphs).toBe(2);
  expect(t.sentences).toBe(2);
});

// Writes Node's expected fingerprints for scripts/devcheck.sh. Skipped in normal runs: the
// 5 MB page makes it slow, and the generated module is only loaded when it runs.
const out = process.env.DEVCHECK_EXPECTED_OUT;
(out ? test : test.skip)(
  'write Node expectations for the on-device devcheck',
  () => {
    const { PAGES, TEXTS } = require('../src/devcheck/fixtures.generated');
    const all = [
      ...PAGES.map((p: { name: string; html: string; bytes: number }) =>
        fingerprintPage(p.name, p.html, p.bytes, 'fallback'),
      ),
      ...TEXTS.map((t: { name: string; text: string }) =>
        fingerprintText(t.name, t.text, 'fallback'),
      ),
    ];
    writeFileSync(
      out as string,
      JSON.stringify(
        all.map(({ name, bytes, hash, paragraphs, sentences }) => ({
          name,
          bytes,
          hash,
          paragraphs,
          sentences,
        })),
      ),
    );
  },
  300_000,
);
```

Run: `npx jest __tests__/devcheck.expected.test.ts`
Expected: FAIL with `Cannot find module '../src/devcheck/fingerprint'`.

- [ ] **Step 4: Implement `src/devcheck/fingerprint.ts`**

```ts
// A deterministic summary of one pipeline run, computed the same way on Hermes (the devcheck
// bundle) and in Node (Jest), so a hash mismatch means the runtimes disagree (roadmap F19).
// Fixture text only; never used on items.
import { extractArticle } from '../extract/extract';
import { splitSharedText } from '../intake/paragraphs';
import { segment, type SegmenterMode } from '../segment/segment';
import type { Paragraph, Sentence } from '../types';

export type Fingerprint = {
  name: string;
  bytes?: number;
  extractMs: number;
  segmentMs: number;
  paragraphs: number;
  chars: number;
  sentences: number;
  poor: boolean;
  hash: string;
};

// FNV-1a needs 32-bit unsigned arithmetic; the bitwise operators are the point here.
/* eslint-disable no-bitwise */
export function fnv1a(s: string, seed = 0x811c9dc5): number {
  let h = seed >>> 0;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h;
}
/* eslint-enable no-bitwise */

function summarize(
  name: string,
  title: string,
  paragraphs: Paragraph[],
  sentences: Sentence[],
): Pick<Fingerprint, 'name' | 'paragraphs' | 'chars' | 'sentences' | 'hash'> {
  let h = fnv1a(title);
  for (const p of paragraphs) h = fnv1a(`${p.kind}\u0000${p.text}\u0000`, h);
  for (const s of sentences) h = fnv1a(`${s.paragraphIndex}:${s.start}:${s.end};`, h);
  return {
    name,
    paragraphs: paragraphs.length,
    chars: paragraphs.reduce((n, p) => n + p.text.length, 0),
    sentences: sentences.length,
    hash: h.toString(16),
  };
}

export function fingerprintPage(
  name: string,
  html: string,
  bytes: number,
  mode: SegmenterMode,
): Fingerprint {
  const t0 = Date.now();
  const ex = extractArticle(html);
  const t1 = Date.now();
  const sentences = segment(ex.paragraphs, new Set(), { mode });
  const t2 = Date.now();
  return {
    ...summarize(name, ex.title, ex.paragraphs, sentences),
    bytes,
    extractMs: t1 - t0,
    segmentMs: t2 - t1,
    poor: ex.poor,
  };
}

export function fingerprintText(
  name: string,
  text: string,
  mode: SegmenterMode,
): Fingerprint {
  const t0 = Date.now();
  const paragraphs = splitSharedText(text);
  const t1 = Date.now();
  const sentences = segment(paragraphs, new Set(), { mode });
  const t2 = Date.now();
  return {
    ...summarize(name, '', paragraphs, sentences),
    extractMs: t1 - t0,
    segmentMs: t2 - t1,
    poor: false,
  };
}
```

Run: `npx jest __tests__/devcheck.expected.test.ts`
Expected: PASS 2 tests, 1 skipped.

```bash
mkdir -p .claude/scratch/devcheck
DEVCHECK_EXPECTED_OUT=.claude/scratch/devcheck/expected-local.json npx jest __tests__/devcheck.expected.test.ts
node -e "const e=require('./.claude/scratch/devcheck/expected-local.json'); console.log(e.map(x=>x.name+' '+x.paragraphs+'p '+x.sentences+'s').join('\n'))"
```

Expected: PASS 3 tests, then five lines with paragraph and sentence counts.

- [ ] **Step 5: The devcheck bundle**

`src/devcheck/DevCheck.tsx`:

```tsx
import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { PAGES, TEXTS } from './fixtures.generated';
import { fingerprintPage, fingerprintText } from './fingerprint';

// Root of the devcheck bundle only (index.devcheck.js). Logs one DEVCHECK line per fixture:
// counts, timings and a hash, never fixture text. 'auto' takes whatever segmenter the
// runtime has, which on Hermes is the fallback (SPIKE-03).
export default function DevCheck() {
  const [status, setStatus] = useState('devcheck running');
  useEffect(() => {
    // Let the first frame draw before the JS thread is busy for seconds.
    const timer = setTimeout(() => {
      const intl = Intl as unknown as { Segmenter?: unknown };
      console.log(
        `DEVCHECK_ENV ${JSON.stringify({
          hermes: 'HermesInternal' in globalThis,
          intlSegmenter: typeof intl.Segmenter === 'function',
        })}`,
      );
      for (const p of PAGES) {
        console.log(`DEVCHECK ${JSON.stringify(fingerprintPage(p.name, p.html, p.bytes, 'auto'))}`);
      }
      for (const t of TEXTS) {
        console.log(`DEVCHECK ${JSON.stringify(fingerprintText(t.name, t.text, 'auto'))}`);
      }
      console.log('DEVCHECK_DONE');
      setStatus('devcheck done');
    }, 500);
    return () => clearTimeout(timer);
  }, []);
  return (
    <View style={styles.root}>
      <Text>{status}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, alignItems: 'center', justifyContent: 'center' },
});
```

`index.devcheck.js`:

```js
/**
 * @format
 */
// Devcheck bundle entry (scripts/devcheck.sh builds with -PreadmeEntryFile). Never the
// product entry: it carries ~6 MB of fixture pages.
import { AppRegistry } from 'react-native';
import DevCheck from './src/devcheck/DevCheck';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => DevCheck);
```

In `android/app/build.gradle`, inside `react { ... }`, replace the commented line `// entryFile = file("../js/MyApplication.android.js")` with:

```groovy
    // The product entry is index.js. scripts/devcheck.sh passes -PreadmeEntryFile to build
    // the devcheck bundle instead (Phase 1 Task 6); nothing else sets it.
    entryFile = file(project.findProperty("readmeEntryFile") ?: "../../index.js")
```

Run: `npm run build:release`
Expected: `built <hash> (dirty)` (uncommitted changes), BUILD SUCCESSFUL: the product entry still bundles.

- [ ] **Step 6: Write `scripts/devcheck.sh` and `scripts/devcheck-report.mjs`**

`scripts/devcheck.sh`:

```bash
#!/usr/bin/env bash
# Phase 1 / roadmap F19: run the real text pipeline (extract + segment) on Hermes in a release
# build, `runs` times in fresh processes, and compare every fingerprint with Node's. Also checks
# SPIKE-02's F17 line with the production extract code.
# Usage: scripts/devcheck.sh [runs=3]
# Exit: 0 parity and F17 hold, 1 mismatch/timeout/F17 miss, 2 app died, 3 no slot, 5 phone
# locked, 6 uncommitted changes. Installs a DEVCHECK build over whatever is on the phone and
# deletes the product APK, so product device commands refuse until npm run build:release.
set -euo pipefail
cd "$(dirname "$0")/.."
RUNS=${1:-3}
. scripts/lib/device.sh
if [ -n "$(git status --porcelain -- . ':(exclude).claude')" ]; then
  echo "uncommitted changes: commit first, so the measured build is named" >&2
  git status --short -- . ':(exclude).claude' >&2
  exit 6
fi
cleanup() { adb shell am force-stop "$PKG" >/dev/null 2>&1 || true; }
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_unlocked
HEAD7=$(git rev-parse --short HEAD)
SCR="$PRIMARY/.claude/scratch/devcheck"
mkdir -p "$SCR"
EXPECTED="$SCR/expected-$HEAD7.json"
DEVICE_OUT="$SCR/device-$HEAD7.txt"
: > "$DEVICE_OUT"

npm run -s devcheck:fixtures >/dev/null
DEVCHECK_EXPECTED_OUT="$EXPECTED" npx jest __tests__/devcheck.expected.test.ts >/dev/null

(cd android && ./gradlew -q assembleRelease -PreadmeEntryFile=../../index.devcheck.js)
APK=android/app/build/outputs/apk/release/app-release.apk
mkdir -p android/app/build/devcheck
mv "$APK" android/app/build/devcheck/app-devcheck.apk
rm -f "$APK.stamp"   # product device commands now refuse until npm run build:release
adb install -r android/app/build/devcheck/app-devcheck.apk >/dev/null
printf '%s\nbranch=%s commit=%s tree=clean at=%s purpose=devcheck\n' \
  "$(git rev-parse --show-toplevel)" "$(git branch --show-current)" "$HEAD7" "$(date -Iseconds)" \
  > "$PRIMARY/.claude/scratch/device-installed-from"
echo "installed DEVCHECK build $HEAD7 on the phone (replaces whatever build was there)"

for run in $(seq "$RUNS"); do
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" >/dev/null
  done_=""
  logs=""
  for _ in $(seq 300); do
    # Read whole, then search (scripts/lib/device.sh, device_has).
    logs=$(adb logcat -d -s ReactNativeJS:I)
    if device_has "$logs" 'DEVCHECK_DONE'; then done_=1; break; fi
    if [ -z "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)" ]; then
      echo "run $run: app process died during the devcheck" >&2
      crash=$(adb logcat -d -b crash,main)
      grep -E "AndroidRuntime|FATAL|libc|OutOfMemory|hermes" <<<"$crash" | tail -20 >&2 || true
      exit 2
    fi
    sleep 1
  done
  [ -n "$done_" ] || { echo "run $run: no DEVCHECK_DONE within 300 s" >&2; exit 1; }
  grep -oE 'DEVCHECK(_ENV)? \{.*\}' <<<"$logs" | sed "s/^/run=$run /" >> "$DEVICE_OUT"
done
node scripts/devcheck-report.mjs "$EXPECTED" "$DEVICE_OUT"
```

`scripts/devcheck-report.mjs`:

```js
// Compares the devcheck's device fingerprints with Node's and applies SPIKE-02's F17 line
// (median extract time: 10 s for the 5 MB page, 1.5 s for every page under 1 MB).
import { readFileSync } from 'node:fs';

const [expectedPath, devicePath] = process.argv.slice(2);
const expected = JSON.parse(readFileSync(expectedPath, 'utf8'));
const runs = new Map();
let env = null;
for (const line of readFileSync(devicePath, 'utf8').split('\n')) {
  const m = /^run=(\d+) DEVCHECK(_ENV)? (\{.*\})$/.exec(line);
  if (!m) continue;
  const d = JSON.parse(m[3]);
  if (m[2]) {
    env = d;
    continue;
  }
  if (!runs.has(d.name)) runs.set(d.name, []);
  runs.get(d.name).push(d);
}

const median = xs => {
  const s = [...xs].sort((a, b) => a - b);
  return s.length ? s[Math.floor(s.length / 2)] : null;
};

let ok = env !== null;
console.log('env', JSON.stringify(env));
for (const e of expected) {
  const rs = runs.get(e.name) ?? [];
  const parity = rs.length > 0 && rs.every(r => r.hash === e.hash);
  const extract = rs.map(r => r.extractMs);
  const med = median(extract);
  const limit = e.bytes === undefined ? null : e.bytes < 1024 * 1024 ? 1500 : 10000;
  const f17 = limit === null || med === null ? 'n/a' : med <= limit ? 'PASS' : 'FAIL';
  if (!parity || f17 === 'FAIL') ok = false;
  console.log(
    JSON.stringify({
      name: e.name,
      bytes: e.bytes,
      runs: rs.length,
      parity,
      paragraphs: e.paragraphs,
      sentences: e.sentences,
      extractMs: extract,
      medianExtractMs: med,
      limitMs: limit,
      f17,
      segmentMs: rs.map(r => r.segmentMs),
    }),
  );
}
console.log(ok ? 'devcheck: PASS' : 'devcheck: FAIL');
process.exit(ok ? 0 : 1);
```

```bash
chmod +x scripts/devcheck.sh
```

- [ ] **Step 7: Gates, then commit (the devcheck refuses a dirty tree)**

```bash
npx prettier --write src/devcheck/fingerprint.ts src/devcheck/DevCheck.tsx index.devcheck.js __tests__/devcheck.expected.test.ts scripts/make-devcheck-fixtures.mjs scripts/devcheck-report.mjs
npm run typecheck && npm run lint && npm test && npm run test:scripts
git add android/app/build.gradle package.json .gitignore __tests__/fixtures/text src/devcheck/fingerprint.ts src/devcheck/DevCheck.tsx index.devcheck.js __tests__/devcheck.expected.test.ts scripts/make-devcheck-fixtures.mjs scripts/devcheck.sh scripts/devcheck-report.mjs
git commit -m "feat(build): devcheck bundle runs the text pipeline on Hermes and compares with Node

Refs REA-<N>"
```

Expected: all gates exit 0; the network guard passes with `src/devcheck` and `index.devcheck.js` included; `git status --short` is clean.

- [ ] **Step 8: Run it on the phone (owner unlocks the phone first)**

```bash
npm run device:devcheck 2>&1 | tee .claude/scratch/devcheck/run-$(git rev-parse --short HEAD).txt
```

Expected: `installed DEVCHECK build <hash>`, then an `env` line with `"hermes":true,"intlSegmenter":false`, then one JSON line per fixture with `"parity":true` and `"runs":3`. `synthetic-5mb` has `"f17":"PASS"` (median at most 10000 ms) and every page under 1 MB has `"f17":"PASS"` (median at most 1500 ms). The last line is `devcheck: PASS`, exit 0.

If parity fails for a fixture: the runtimes disagree. Find the first differing paragraph or sentence (add a temporary DEVCHECK line with per-paragraph hashes on a scratch commit, not this branch), fix the code so both agree, commit, rerun. If F17 fails, record the numbers; that reopens SPIKE-02's decision and stops this task for the owner.

Afterwards restore the product build on the phone:

```bash
npm run build:release && npm run device:smoke
```

Expected: `built <hash> (clean)`, `device:smoke PASS`.

---

### Task 7: Docs and ship

**Files:**
- Modify: `CONTEXT.md` (source layout, module list), `AGENTS.md` (Known state, Quality gates)

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Update the docs to the real tree**

- `CONTEXT.md`, the source layout section: list `src/types.ts`, `src/intake/`, `src/extract/`, `src/segment/`, `src/trim/`, `src/devcheck/` (devcheck bundle only), `index.devcheck.js`, each with its one-line responsibility from this plan's File structure table. Add `trim` (R-M05 math) beside the srs.md TypeScript modules table's five modules, noting it is pure functions used by `reader` and `library`.
- `AGENTS.md` Known state: replace "Scaffold only: RN 0.87.1 template app, gates and CI. No product modules yet." with "Text pipeline (Phase 1, REA-<N>): `intake`, `extract`, `segment`, `trim` in TS, pure functions with no native, storage or UI; verified on Hermes by `npm run device:devcheck` (<date>, build <hash>: parity on all fixtures, 5 MB median <n> ms). No native module, storage, playback or screens yet." using the Step 8 numbers.
- `AGENTS.md` Quality gates block: add `npm run device:devcheck   # devcheck bundle: text pipeline on Hermes vs Node, F17 timings (replaces the phone's build)`.

```bash
grep -rnP '\x{2014}' AGENTS.md CONTEXT.md src __tests__ scripts docs/superpowers/plans/2026-10-02-phase-1-text-pipeline.md || echo "no em-dashes"
git add AGENTS.md CONTEXT.md
git commit -m "docs: record the Phase 1 text pipeline in AGENTS.md and CONTEXT.md

Refs REA-<N>"
```

- [ ] **Step 2: `/ship`**

Run `/ship` on the branch. The PR body's Testing section quotes the Step 8 report lines verbatim (parity per fixture, F17 medians) as the on-device evidence, and the `device:smoke` result after restoring the product build. Non-negotiables to tick with evidence: no JS network (the guard test), no logging in the pipeline, positions as offsets, trim non-destructive. Linear: no `In Review` status exists, so the issue stays In Progress with the PR link as a comment (`.claude/linear.md`).
