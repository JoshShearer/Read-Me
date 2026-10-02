# Phase 2: Native Library and Intake Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A link or text shared to Read Me becomes a stored item with no JS runtime involved. A link is fetched natively under R-M03's limits and extracted by JS when it runs. The app shows the list of items with their states.

**Architecture:**
- Kotlin owns the data (ADR 0001): a `Store` on `android.database.sqlite` holds items, paragraphs, cuts and positions. A fetched page's HTML waits as a file under `files/bodies/` (ADR 0007).
- A translucent `ShareActivity` classifies the share with a Kotlin twin of the Phase 1 TS classifier, stores the item and enqueues a WorkManager `FetchWorker`. The worker runs `Fetcher` (OkHttp, R-M03 limits) and moves the item to `fetched` or `fetch-failed`.
- On every process start, `Recovery` fails `fetching` items whose work is gone.
- JS reaches the Store only through the `ReadMeSpeech` TurboModule. The TS `library` facade drains `fetched` items through Phase 1's `extractArticle`.
- Release builds install a runtime stub over the JS network APIs (F16).

**Tech Stack:**
- React Native 0.87.1, Hermes, TypeScript strict, Jest.
- Kotlin 2.2.0, AGP from RN's plugin, compileSdk 37, minSdk 24, targetSdk 36.
- OkHttp 4.9.2 (already on RN's classpath).
- androidx.work 2.12.0.
- Tests: JUnit 4.13.2, Robolectric 4.17 (sandbox SDK 35), androidx.test core 1.7.0, work-testing 2.12.0, MockWebServer 4.9.2.

**Spec:** `srs.md` as amended 2026-10-02: R-M02, R-M03, R-M04, R-M09, R-M10, R-M11 and "Data model". Also ADR 0001, ADR 0006, ADR 0007, and `AGENTS.md` non-negotiables 1-3, 6, 7, 10, 12 and 14.

## Global Constraints

- No item text in any log: no title, URL path or query, body or paragraph, not even in an exception message. Logs carry ids, states, reason classes, counts and durations (AGENTS.md 1).
- `Fetcher` is the only outbound network call site. No JS source references `fetch`, `XMLHttpRequest` or `WebSocket`, except `src/net/guard.ts`, which names them only to stub them.
  - The word "fetch" on its own trips the JS guard, even in a comment. Write "the network request", "Fetcher", "fetched" or "fetching" instead.
- `Fetcher` limits (AGENTS.md 7, R-M03):
  - `CookieJar.NO_COOKIES` and no cache.
  - Redirects followed manually: at most 5, to `http(s)` targets only.
  - A 20 s total deadline across all hops.
  - A 5 MB cap enforced while streaming: the read aborts at the 5,242,881st byte.
- Item states: `fetching | fetched | fetch-failed | extract-poor | ready`. Archive is `archivedAt`; the first open is `openedAt` (ADR 0007).
- Positions are character offsets; cuts are a separate set. Trim never overwrites paragraph text (AGENTS.md 10, 12).
- F-Droid-clean: after any dependency change, the resolved Gradle tree has no `gms`, `firebase` or `crashlytics` (AGENTS.md 14). Every new dependency is OSI-licensed (WorkManager, Room and androidx.sqlite are Apache-2.0).
- No em-dashes anywhere.

## Review Focus

1. **The process dies mid-fetch.** WorkManager re-runs RUNNING work after the process restarts, so the item must stay `fetching`, not be marked `interrupted` while its work is still queued. A finished or missing work record means `interrupted`. Pinned in Task 5 (`RecoveryTest`).
2. **Two network events race.** A fetch completes while JS is already draining. The newly `fetched` item must still be extracted, without waiting for the next app start. Pinned in Task 6 (`library.test.ts`, "a second drain request during a drain runs again").
3. **A `fetched` item whose body file is gone** (storage cleared, or a crash between the write and the state change). It must not sit in `fetched` forever: it becomes `fetch-failed: interrupted`. Pinned in Task 3 (`StoreTest`).
4. **A redirect to another host sets a cookie.** The next hop must not send it. Pinned in Task 4 (`FetcherTest`).
5. **A share of a long text** (hundreds of KB in one paragraph) must store and read back intact. Pinned in Task 3 (`StoreTest`).

## Verified before writing (2026-10-02)

Run in a throwaway worktree of `main` (`c7fafd5`) on this machine:

- **Robolectric 4.17:**
  - Its SDK 36 sandbox fails: "Android SDK 36 requires Java 21 (have Java 17)". The Gradle unit-test JVM here is 17. So `robolectric.properties` sets `sdk=35`.
  - Robolectric instantiates `MainApplication`, whose React Native SoLoader throws a NullPointerException under the JVM. So `robolectric.properties` also sets `application=android.app.Application`.
  - With both set, three probe tests passed: an in-memory SQLite table, a MockWebServer round trip on RN's OkHttp 4.9.2, and a WorkManager job run through `WorkManagerTestInitHelper`.
- **Codegen:** `codegenConfig` in `package.json` (`jsSrcsDir: src/native`, `javaPackageName: io.loopstring.readme.spec`) generated `io/loopstring/readme/spec/NativeReadMeSpeechSpec.java`. A Kotlin `NativeReadMeSpeechSpec` subclass, registered through a `BaseReactPackage` in `MainApplication`, built with `assembleRelease`.
  - **Not run: the module call on the phone** (the phone was locked). Task 6 Step 9 is the first runtime check.
- **Release tree:** with `androidx.work:work-runtime-ktx:2.12.0` added, `releaseRuntimeClasspath` resolves OkHttp 4.9.2 and okio 2.9.0, plus work-runtime 2.12.0, room-runtime 2.7.0 and sqlite 2.5.0. Zero matches for `gms`, `firebase`, `crashlytics` or `play-services`.
- **Rulings recorded here:**
  - **Text item title:** the first paragraph, cut at the last space within 80 characters, with "…" appended when cut. A link item's title is the URL's host without `www.` until extraction gives one.
  - **Reason classes:** `fetch-failed` reasons are `timeout`, `too-large`, `too-many-redirects`, `http-<status>`, `offline` (unknown host, refused, unreachable), `network` (any other I/O error, TLS included), `unsupported-redirect` (a `Location` that is not http or https), `bad-url` and `interrupted`. R-M10's list gains `network`, `unsupported-redirect` and `bad-url`; Task 8 amends R-M10.
  - **A resumed request:** WorkManager re-runs a fetch whose process died mid-request. That counts as the same user request (R-M03 as amended), because the first GET never completed.
  - **Offline shares:** a fetch has no network constraint. An offline share fails at once as `offline`, visible with Retry, rather than sitting in `fetching`.
  - **Body storage:** a body is a file, not a SQLite row, because Android's CursorWindow (2 MB) cannot hold a 5 MB body.

## File structure

| Path | Responsibility |
|---|---|
| `__tests__/fixtures/intake-vectors.json` | Shared classify/split cases; the TS and Kotlin twins must both pass them |
| `__tests__/intakeVectors.test.ts` | TS side of the vectors |
| `android/app/src/main/java/io/loopstring/readme/intake/Intake.kt` | R-M02 classify, R-M04 text split, text title (Kotlin twin) |
| `android/app/src/main/java/io/loopstring/readme/store/Store.kt` | SQLite schema v1, item lifecycle, body files, change events |
| `android/app/src/main/java/io/loopstring/readme/store/ItemEvents.kt` | Process-wide change listeners |
| `android/app/src/main/java/io/loopstring/readme/fetch/Fetcher.kt` | R-M03 HTTP GET with the limits |
| `android/app/src/main/java/io/loopstring/readme/fetch/FetchWorker.kt` | WorkManager job: fetch one item, store the outcome |
| `android/app/src/main/java/io/loopstring/readme/fetch/Recovery.kt` | R-M02 start-up recovery |
| `android/app/src/main/java/io/loopstring/readme/ShareActivity.kt` | `ACTION_SEND text/plain` entry |
| `android/app/src/main/java/io/loopstring/readme/ReadMeSpeechModule.kt`, `ReadMeSpeechPackage.kt` | TurboModule over the Store |
| `android/app/src/test/...` | Robolectric and MockWebServer tests |
| `src/native/NativeReadMeSpeech.ts` | Codegen spec |
| `src/library/library.ts` | TS facade: list, get, drain `fetched`, actions, change events |
| `src/net/guard.ts`, `src/net/installGuard.ts` | F16 runtime stub, installed first in release builds |
| `App.tsx` | Plain list of items and states (the real UI is Phase 4) |
| `scripts/device-intake-e2e.sh` | On-device E2E: share link, text, dead link; check states and logs |

---

### Task 1: Dependencies, test toolchain, intake vectors and the Kotlin `Intake`

**Files:**
- Modify: `android/app/build.gradle` (dependencies, `testOptions`)
- Create: `android/app/src/test/resources/robolectric.properties`
- Create: `__tests__/fixtures/intake-vectors.json`, `__tests__/intakeVectors.test.ts`
- Create: `android/app/src/main/java/io/loopstring/readme/intake/Intake.kt`
- Test: `android/app/src/test/java/io/loopstring/readme/intake/IntakeTest.kt`

**Interfaces:**
- Consumes: `classifyShare`, `splitSharedText` (Phase 1, `src/intake/`).
- Produces: `Intake.classify(shared: String): Intake.Share` (`Link(url)`, `Text(text)`, `Empty`); `Intake.splitParagraphs(text: String): List<String>`; `Intake.textTitle(paragraphs: List<String>): String`; `Intake.hostTitle(url: String): String`.

- [ ] **Step 1: Branch and dependencies**

```bash
git checkout -b feature/rea-16-native-library-intake main
```

In `android/app/build.gradle`, inside `android { ... }` right after `namespace "io.loopstring.readme"`:

```groovy
    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources.
            includeAndroidResources = true
        }
    }
```

At the top of `dependencies { ... }`, after `implementation("com.facebook.react:react-android")`:

```groovy
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.work:work-testing:2.12.0")
    // Matches the OkHttp 4.9.2 React Native already puts on the classpath.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.9.2")
```

`android/app/src/test/resources/robolectric.properties`:

```properties
# SDK 36's sandbox needs Java 21 and the unit-test JVM here is 17. MainApplication loads
# React Native's native libraries, which Robolectric cannot; tests use a plain Application.
sdk=35
application=android.app.Application
```

- [ ] **Step 2: Write the shared vectors and the TS test**

`__tests__/fixtures/intake-vectors.json`:

```json
{
  "classify": [
    { "in": "https://x.com/a", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "Title https://x.com/a?q=1", "out": { "kind": "link", "url": "https://x.com/a?q=1" } },
    { "in": "Read this: https://x.com/a.", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "(see https://x.com/a)", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "\"https://x.com/a\"", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "\u201chttps://x.com/a\u201d", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "Title https://x.com/a\u3002", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "https://x.com/a\uff0cnext", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "Look\u2014https://x.com/a\u2014wow", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "Article https://x.com/a\u200b more", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "https://en.wikipedia.org/wiki/Foo_(bar)", "out": { "kind": "link", "url": "https://en.wikipedia.org/wiki/Foo_(bar)" } },
    { "in": "HTTPS://X.com/a", "out": { "kind": "link", "url": "HTTPS://X.com/a" } },
    { "in": "https://x.com/a https://x.com/a", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "caf\u00e9https://x.com/a", "out": { "kind": "link", "url": "https://x.com/a" } },
    { "in": "https://x.com/a and https://y.com/b", "out": { "kind": "text" } },
    { "in": "xhttps://x.com/a", "out": { "kind": "text" } },
    { "in": "ftp://x.com/a", "out": { "kind": "text" } },
    { "in": "www.x.com/a", "out": { "kind": "text" } },
    { "in": "just some words", "out": { "kind": "text" } },
    { "in": "", "out": { "kind": "empty" } },
    { "in": "  \n\t ", "out": { "kind": "empty" } },
    { "in": "\u00a0\u3000\ufeff", "out": { "kind": "empty" } }
  ],
  "split": [
    { "in": "a\n\nb", "out": ["a", "b"] },
    { "in": "a\nb", "out": ["a b"] },
    { "in": "a\r\n\r\nb", "out": ["a", "b"] },
    { "in": "a\n  \nb", "out": ["a", "b"] },
    { "in": "a\n\u3000\nb", "out": ["a", "b"] },
    { "in": "a\u2029b", "out": ["a", "b"] },
    { "in": "\n\n a  b \n\n", "out": ["a b"] },
    { "in": "x\u00a0\u00a0y", "out": ["x y"] },
    { "in": "", "out": [] }
  ]
}
```

`__tests__/intakeVectors.test.ts`:

```ts
/// <reference types="node" />
// Node-only test. The Kotlin share path (Intake.kt) has no JS runtime, so it is a twin of
// classifyShare and splitSharedText; both are pinned to the same vectors.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { classifyShare } from '../src/intake/classify';
import { splitSharedText } from '../src/intake/paragraphs';

type Vectors = {
  classify: { in: string; out: { kind: string; url?: string } }[];
  split: { in: string; out: string[] }[];
};
const vectors: Vectors = JSON.parse(
  readFileSync(join(__dirname, 'fixtures/intake-vectors.json'), 'utf8'),
);

test.each(vectors.classify.map(v => [JSON.stringify(v.in), v] as const))(
  'classify %s',
  (_, v) => {
    const got = classifyShare(v.in);
    expect(got.kind).toBe(v.out.kind);
    if (v.out.kind === 'link' && got.kind === 'link')
      expect(got.url).toBe(v.out.url);
  },
);

test.each(vectors.split.map(v => [JSON.stringify(v.in), v] as const))(
  'split %s',
  (_, v) => {
    expect(splitSharedText(v.in).map(p => p.text)).toEqual(v.out);
  },
);
```

Run: `npx jest __tests__/intakeVectors.test.ts`
Expected: PASS for all cases (31 tests). The vectors describe Phase 1's behaviour. If one fails, the vector is wrong, not the code: fix the vector.

- [ ] **Step 3: Write the failing Kotlin test**

`android/app/src/test/java/io/loopstring/readme/intake/IntakeTest.kt`:

```kotlin
package io.loopstring.readme.intake

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for org.json (the plain JVM test classpath has only stubs).
@RunWith(RobolectricTestRunner::class)
class IntakeTest {
  // Gradle runs unit tests with the module directory (android/app) as the working directory.
  private val vectors = JSONObject(File("../../__tests__/fixtures/intake-vectors.json").readText())

  @Test fun classifyMatchesTheSharedVectors() {
    val cases = vectors.getJSONArray("classify")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val input = c.getString("in")
      val out = c.getJSONObject("out")
      val got = Intake.classify(input)
      val expected = when (out.getString("kind")) {
        "link" -> Intake.Share.Link(out.getString("url"))
        "text" -> Intake.Share.Text(input)
        else -> Intake.Share.Empty
      }
      assertEquals("case $i", expected, got)
    }
  }

  @Test fun splitMatchesTheSharedVectors() {
    val cases = vectors.getJSONArray("split")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val out = c.getJSONArray("out")
      val expected = (0 until out.length()).map { out.getString(it) }
      assertEquals("case $i", expected, Intake.splitParagraphs(c.getString("in")))
    }
  }

  @Test fun textTitleIsTheFirstParagraphCutAtAWordWithin80() {
    assertEquals("Short note", Intake.textTitle(listOf("Short note", "more")))
    val long = "word ".repeat(30).trim()
    val title = Intake.textTitle(listOf(long))
    assertEquals(true, title.endsWith("\u2026"))
    assertEquals(true, title.length <= 81)
    assertEquals(false, title.dropLast(1).endsWith(" "))
  }

  @Test fun hostTitleDropsWww() {
    assertEquals("example.com", Intake.hostTitle("https://www.example.com/a/b?q=1"))
    assertEquals("news.example.org", Intake.hostTitle("http://news.example.org"))
  }
}
```

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.intake.IntakeTest')`
Expected: FAIL to compile: "Unresolved reference 'Intake'".

- [ ] **Step 4: Implement `Intake`**

`android/app/src/main/java/io/loopstring/readme/intake/Intake.kt`:

```kotlin
package io.loopstring.readme.intake

import android.net.Uri

/**
 * R-M02 classification and R-M04 text-item paragraphs for the share activity, which runs with
 * no JS runtime. A twin of src/intake/classify.ts and paragraphs.ts; both pass
 * __tests__/fixtures/intake-vectors.json. Never logs and never builds a message from the text.
 */
object Intake {
  sealed class Share {
    data class Link(val url: String) : Share()
    data class Text(val text: String) : Share()
    object Empty : Share()
  }

  // JavaScript's \s, spelled out: Java's \s is ASCII-only.
  private const val JS_SPACE =
    "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
  private const val JS_SPACE_NO_NL =
    "\\t\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
  private val SPACE_CHARS: Set<Char> =
    ("\t\n\u000B\u000C\r \u00a0\u1680\u2028\u2029\u202f\u205f\u3000\ufeff" +
        (0x2000..0x200A).map { it.toChar() }.joinToString("")).toSet()

  // JS's \b before "https" is an ASCII word boundary, hence the lookbehind.
  private val URL = Regex(
    "(?<![A-Za-z0-9_])https?://[^$JS_SPACE<>\"'`\\u200B-\\u200F\\u2060\\uFEFF\\u2013\\u2014" +
      "\\u201C\\u201D\\u2018\\u2019\\u00AB\\u00BB\\u2039\\u203A\\u300C\\u300D\\u300E\\u300F" +
      "\\u3001\\u3002\\uFF0C\\uFF1B\\uFF1A\\uFF01\\uFF1F\\u2026]+",
    RegexOption.IGNORE_CASE,
  )
  private const val TRAILING = ".,;:!?'\""
  private val PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')
  private val CRLF = Regex("\\r\\n?")
  private val BLANK_LINE = Regex("\\n[$JS_SPACE_NO_NL]*\\n")
  private val SPACES = Regex("[$JS_SPACE]+")
  private const val TITLE_MAX = 80

  private fun jsTrim(s: String): String = s.trim { it in SPACE_CHARS }

  private fun trimUrl(raw: String): String {
    var url = raw
    while (url.isNotEmpty()) {
      val last = url.last()
      if (last in TRAILING) {
        url = url.dropLast(1)
        continue
      }
      val open = PAIRS[last]
      if (open != null && url.count { it == open } < url.count { it == last }) {
        url = url.dropLast(1)
        continue
      }
      break
    }
    return url
  }

  fun classify(shared: String): Share {
    if (jsTrim(shared).isEmpty()) return Share.Empty
    val urls = URL.findAll(shared).map { trimUrl(it.value) }.toSet()
    return if (urls.size == 1) Share.Link(urls.first()) else Share.Text(shared)
  }

  fun splitParagraphs(text: String): List<String> =
    text.replace(CRLF, "\n")
      .replace("\u2029", "\n\n")
      .split(BLANK_LINE)
      .map { jsTrim(it.replace(SPACES, " ")) }
      .filter { it.isNotEmpty() }

  /** The first paragraph, cut at the last space within 80 characters. */
  fun textTitle(paragraphs: List<String>): String {
    val first = paragraphs.firstOrNull().orEmpty()
    if (first.length <= TITLE_MAX) return first
    val cut = first.lastIndexOf(' ', TITLE_MAX).takeIf { it > 0 } ?: TITLE_MAX
    return first.substring(0, cut).trimEnd() + "\u2026"
  }

  /** A link item's title until extraction gives one: the host, without "www.". */
  fun hostTitle(url: String): String =
    (Uri.parse(url).host ?: url).lowercase().removePrefix("www.")
}
```

- [ ] **Step 5: Run the Kotlin test**

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.intake.IntakeTest')`
Expected: PASS, 4 tests. A failing vector means the twins disagree. Fix the Kotlin side until it matches the JS behaviour the vector pins; never edit a vector to suit Kotlin.

- [ ] **Step 6: Check the dependency tree and licences**

```bash
(cd android && ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath) \
  | grep -ciE 'com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play'
node scripts/check-licenses.mjs
```

Expected: `0` hits. `check-licenses.mjs` passes, since it covers npm only; the new Gradle dependencies are Apache-2.0 (androidx, Robolectric MIT, OkHttp Apache-2.0).

- [ ] **Step 7: Commit**

```bash
git add android/app/build.gradle android/app/src/test __tests__/fixtures/intake-vectors.json \
  __tests__/intakeVectors.test.ts android/app/src/main/java/io/loopstring/readme/intake
git commit -m "feat(share): Kotlin twin of share classification, pinned to shared vectors

Refs REA-16"
```

---

### Task 2: `ItemEvents` and the `Store` schema

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/store/ItemEvents.kt`
- Create: `android/app/src/main/java/io/loopstring/readme/store/Store.kt`
- Test: `android/app/src/test/java/io/loopstring/readme/store/StoreTest.kt`

**Interfaces:**
- Produces:
  - `ItemEvents.add(l: () -> Unit)`, `ItemEvents.remove(l)`, `ItemEvents.changed()`.
  - `data class ItemRow(id: Long, kind: String, url: String?, title: String, site: String?, byline: String?, createdAt: Long, state: String, failReason: String?, openedAt: Long?, archivedAt: Long?)`.
  - `data class ParagraphRow(kind: String, text: String)`.
  - `object States { FETCHING, FETCHED, FETCH_FAILED, EXTRACT_POOR, READY }`.
  - `Store.get(context): Store`, `Store.resetForTest()`.
  - `Store` methods: `insertLink(url, title, now): Long`, `insertText(title, paragraphs: List<String>, now): Long`, `item(id): ItemRow?`, `items(): List<ItemRow>`, `paragraphs(id): List<ParagraphRow>`, `cuts(id): List<Int>`, `idsInState(state): List<Long>`, `delete(id)`.

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/io/loopstring/readme/store/StoreTest.kt`:

```kotlin
package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreTest {
  private lateinit var ctx: Context
  private lateinit var store: Store

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    store = Store.get(ctx)
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aLinkStartsFetchingWithItsHostAsTitle() {
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    val item = store.item(id)!!
    assertEquals("link", item.kind)
    assertEquals("https://example.com/a", item.url)
    assertEquals("example.com", item.title)
    assertEquals(States.FETCHING, item.state)
    assertNull(item.openedAt)
    assertNull(item.archivedAt)
  }

  @Test fun aTextIsReadyWithItsParagraphsInOrder() {
    val id = store.insertText("First", listOf("First", "Second", "Third"), 1000)
    assertEquals(States.READY, store.item(id)!!.state)
    assertEquals(
      listOf(ParagraphRow("p", "First"), ParagraphRow("p", "Second"), ParagraphRow("p", "Third")),
      store.paragraphs(id),
    )
  }

  @Test fun itemsAreNewestFirst() {
    val a = store.insertLink("https://a.com", "a.com", 1000)
    val b = store.insertLink("https://b.com", "b.com", 2000)
    val c = store.insertLink("https://c.com", "c.com", 2000)
    assertEquals(listOf(c, b, a), store.items().map { it.id })
  }

  @Test fun aLongSingleParagraphRoundTrips() {
    // Review Focus 5: hundreds of KB in one paragraph.
    val big = "x".repeat(600_000)
    val id = store.insertText("t", listOf(big), 1000)
    assertEquals(big, store.paragraphs(id).single().text)
  }

  @Test fun deleteRemovesTheItemAndEverythingUnderIt() {
    val id = store.insertText("t", listOf("a", "b"), 1000)
    store.delete(id)
    assertNull(store.item(id))
    assertTrue(store.paragraphs(id).isEmpty())
    assertTrue(store.cuts(id).isEmpty())
  }

  @Test fun everyWriteNotifiesListeners() {
    var n = 0
    val l: () -> Unit = { n++ }
    ItemEvents.add(l)
    try {
      val id = store.insertLink("https://a.com", "a.com", 1000)
      store.delete(id)
    } finally {
      ItemEvents.remove(l)
    }
    assertEquals(2, n)
  }
}
```

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.store.StoreTest')`
Expected: FAIL to compile: "Unresolved reference 'Store'".

- [ ] **Step 2: Implement `ItemEvents`**

`android/app/src/main/java/io/loopstring/readme/store/ItemEvents.kt`:

```kotlin
package io.loopstring.readme.store

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide "the library changed" signal. The Store fires it after every write. The
 * ReadMeSpeech module forwards it to JS when JS is alive, and nothing depends on that.
 */
object ItemEvents {
  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  fun add(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun remove(listener: () -> Unit) {
    listeners.remove(listener)
  }

  fun changed() {
    for (l in listeners) l()
  }
}
```

- [ ] **Step 3: Implement the `Store` (schema and the methods this task tests)**

`android/app/src/main/java/io/loopstring/readme/store/Store.kt`:

```kotlin
package io.loopstring.readme.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.VisibleForTesting
import java.io.File

data class ItemRow(
  val id: Long,
  val kind: String,
  val url: String?,
  val title: String,
  val site: String?,
  val byline: String?,
  val createdAt: Long,
  val state: String,
  val failReason: String?,
  val openedAt: Long?,
  val archivedAt: Long?,
)

data class ParagraphRow(val kind: String, val text: String)

object States {
  const val FETCHING = "fetching"
  const val FETCHED = "fetched"
  const val FETCH_FAILED = "fetch-failed"
  const val EXTRACT_POOR = "extract-poor"
  const val READY = "ready"
}

/**
 * The library (ADR 0001: Kotlin owns the database; JS goes through ReadMeSpeech). Item
 * lifecycle per ADR 0007. A fetched page's HTML is a file under files/bodies until JS extracts
 * it: a CursorWindow (2 MB) cannot hold a 5 MB body. Every write fires ItemEvents.changed().
 * Nothing here logs.
 */
class Store private constructor(context: Context) :
  SQLiteOpenHelper(context, DB_NAME, null, VERSION) {

  private val bodies = File(context.filesDir, "bodies")

  override fun onConfigure(db: SQLiteDatabase) {
    db.setForeignKeyConstraintsEnabled(true)
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        kind TEXT NOT NULL CHECK (kind IN ('link', 'text')),
        url TEXT,
        title TEXT NOT NULL,
        site TEXT,
        byline TEXT,
        created_at INTEGER NOT NULL,
        state TEXT NOT NULL CHECK (state IN
          ('fetching', 'fetched', 'fetch-failed', 'extract-poor', 'ready')),
        fail_reason TEXT,
        opened_at INTEGER,
        archived_at INTEGER)""",
    )
    db.execSQL(
      """CREATE TABLE paragraphs (
        item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
        idx INTEGER NOT NULL,
        kind TEXT NOT NULL CHECK (kind IN ('p', 'heading', 'li')),
        text TEXT NOT NULL,
        PRIMARY KEY (item_id, idx))""",
    )
    db.execSQL(
      """CREATE TABLE cuts (
        item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
        paragraph_index INTEGER NOT NULL,
        PRIMARY KEY (item_id, paragraph_index))""",
    )
    // Written by PlaybackService in Phase 3 (R-M11): a character offset, never a sentence.
    db.execSQL(
      """CREATE TABLE positions (
        item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
        paragraph_index INTEGER NOT NULL,
        char_offset INTEGER NOT NULL,
        updated_at INTEGER NOT NULL)""",
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Version 1 is the first schema; a later version adds its migration here.
    throw IllegalStateException("no migration from $oldVersion to $newVersion")
  }

  fun insertLink(url: String, title: String, now: Long): Long {
    val id = writableDatabase.insertOrThrow(
      "items",
      null,
      ContentValues().apply {
        put("kind", "link")
        put("url", url)
        put("title", title)
        put("created_at", now)
        put("state", States.FETCHING)
      },
    )
    ItemEvents.changed()
    return id
  }

  fun insertText(title: String, paragraphs: List<String>, now: Long): Long {
    val db = writableDatabase
    val id: Long
    db.beginTransaction()
    try {
      id = db.insertOrThrow(
        "items",
        null,
        ContentValues().apply {
          put("kind", "text")
          put("title", title)
          put("created_at", now)
          put("state", States.READY)
        },
      )
      writeParagraphs(db, id, paragraphs.map { ParagraphRow("p", it) })
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ItemEvents.changed()
    return id
  }

  fun item(id: Long): ItemRow? =
    readableDatabase.rawQuery("SELECT * FROM items WHERE id = ?", arrayOf(id.toString())).use {
      if (it.moveToFirst()) it.toItem() else null
    }

  fun items(): List<ItemRow> =
    readableDatabase.rawQuery("SELECT * FROM items ORDER BY created_at DESC, id DESC", null).use {
      val out = ArrayList<ItemRow>(it.count)
      while (it.moveToNext()) out.add(it.toItem())
      out
    }

  fun paragraphs(id: Long): List<ParagraphRow> =
    readableDatabase.rawQuery(
      "SELECT kind, text FROM paragraphs WHERE item_id = ? ORDER BY idx",
      arrayOf(id.toString()),
    ).use {
      val out = ArrayList<ParagraphRow>(it.count)
      while (it.moveToNext()) out.add(ParagraphRow(it.getString(0), it.getString(1)))
      out
    }

  fun cuts(id: Long): List<Int> =
    readableDatabase.rawQuery(
      "SELECT paragraph_index FROM cuts WHERE item_id = ? ORDER BY paragraph_index",
      arrayOf(id.toString()),
    ).use {
      val out = ArrayList<Int>(it.count)
      while (it.moveToNext()) out.add(it.getInt(0))
      out
    }

  fun idsInState(state: String): List<Long> =
    readableDatabase.rawQuery("SELECT id FROM items WHERE state = ?", arrayOf(state)).use {
      val out = ArrayList<Long>(it.count)
      while (it.moveToNext()) out.add(it.getLong(0))
      out
    }

  /** Deletes the item, its paragraphs, cuts, position and any waiting body (R-M11, AGENTS.md 3). */
  fun delete(id: Long) {
    writableDatabase.delete("items", "id = ?", arrayOf(id.toString()))
    bodyFile(id).delete()
    ItemEvents.changed()
  }

  internal fun bodyFile(id: Long) = File(bodies, "$id.html")

  private fun writeParagraphs(db: SQLiteDatabase, id: Long, paragraphs: List<ParagraphRow>) {
    db.delete("paragraphs", "item_id = ?", arrayOf(id.toString()))
    val stmt = db.compileStatement(
      "INSERT INTO paragraphs (item_id, idx, kind, text) VALUES (?, ?, ?, ?)",
    )
    paragraphs.forEachIndexed { i, p ->
      stmt.clearBindings()
      stmt.bindLong(1, id)
      stmt.bindLong(2, i.toLong())
      stmt.bindString(3, p.kind)
      stmt.bindString(4, p.text)
      stmt.executeInsert()
    }
  }

  private fun Cursor.str(col: String): String? =
    getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

  private fun Cursor.long(col: String): Long? =
    getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

  private fun Cursor.toItem() = ItemRow(
    id = long("id")!!,
    kind = str("kind")!!,
    url = str("url"),
    title = str("title")!!,
    site = str("site"),
    byline = str("byline"),
    createdAt = long("created_at")!!,
    state = str("state")!!,
    failReason = str("fail_reason"),
    openedAt = long("opened_at"),
    archivedAt = long("archived_at"),
  )

  companion object {
    private const val DB_NAME = "readme.db"
    private const val VERSION = 1

    @Volatile private var instance: Store? = null

    fun get(context: Context): Store =
      instance ?: synchronized(this) {
        instance ?: Store(context.applicationContext).also { instance = it }
      }

    /** Robolectric gives each test a fresh app directory; drop the one bound to the last. */
    @VisibleForTesting
    fun resetForTest() {
      synchronized(this) {
        instance?.close()
        instance = null
      }
    }
  }
}
```

- [ ] **Step 4: Run the test**

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.store.StoreTest')`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/store \
  android/app/src/test/java/io/loopstring/readme/store
git commit -m "feat(store): SQLite schema v1 and item insert, read and delete

Refs REA-16"
```

---

### Task 3: The item lifecycle in the `Store`

**Files:**
- Modify: `android/app/src/main/java/io/loopstring/readme/store/Store.kt`
- Test: `android/app/src/test/java/io/loopstring/readme/store/StoreLifecycleTest.kt`

**Interfaces:**
- Consumes: Task 2's `Store`, `States`, `ParagraphRow`.
- Produces (all on `Store`):
  - `fetchSucceeded(id, html: String)`: writes the body, then sets `fetched`.
  - `fetchFailed(id, reason: String)`.
  - `beginRetry(id): Boolean`: `fetch-failed` to `fetching`.
  - `bodyOrFail(id): String?`.
  - `completeExtraction(id, title, site: String?, byline: String?, paragraphs: List<ParagraphRow>, poor: Boolean): Boolean`.
  - `markOpened(id, now)`, `setCut(id, paragraphIndex: Int, cut: Boolean)`, `archive(id, now)`, `restore(id)`.

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/io/loopstring/readme/store/StoreLifecycleTest.kt`:

```kotlin
package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreLifecycleTest {
  private lateinit var store: Store
  private var link = 0L

  @Before fun setUp() {
    store = Store.get(ApplicationProvider.getApplicationContext<Context>())
    link = store.insertLink("https://example.com/a", "example.com", 1000)
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aSuccessfulFetchStoresTheBodyAndMovesToFetched() {
    store.fetchSucceeded(link, "<html>page</html>")
    assertEquals(States.FETCHED, store.item(link)!!.state)
    assertEquals("<html>page</html>", store.bodyOrFail(link))
  }

  @Test fun aFailedFetchKeepsTheItemWithItsReason() {
    store.fetchFailed(link, "timeout")
    val item = store.item(link)!!
    assertEquals(States.FETCH_FAILED, item.state)
    assertEquals("timeout", item.failReason)
  }

  @Test fun retryIsAllowedOnlyFromFetchFailed() {
    assertFalse(store.beginRetry(link))
    store.fetchFailed(link, "offline")
    assertTrue(store.beginRetry(link))
    val item = store.item(link)!!
    assertEquals(States.FETCHING, item.state)
    assertNull(item.failReason)
  }

  @Test fun extractionStoresParagraphsSetsTheStateAndDeletesTheBody() {
    store.fetchSucceeded(link, "<html/>")
    val ok = store.completeExtraction(
      link, "A Title", "Example", "Ann", listOf(ParagraphRow("heading", "H"), ParagraphRow("p", "Body")), poor = false,
    )
    assertTrue(ok)
    val item = store.item(link)!!
    assertEquals(States.READY, item.state)
    assertEquals("A Title", item.title)
    assertEquals("Example", item.site)
    assertEquals("Ann", item.byline)
    assertEquals(listOf(ParagraphRow("heading", "H"), ParagraphRow("p", "Body")), store.paragraphs(link))
    assertFalse(store.bodyFile(link).exists())
  }

  @Test fun aPoorExtractionIsExtractPoor() {
    store.fetchSucceeded(link, "<html/>")
    store.completeExtraction(link, "T", null, null, listOf(ParagraphRow("p", "short")), poor = true)
    assertEquals(States.EXTRACT_POOR, store.item(link)!!.state)
  }

  @Test fun extractionIsRefusedUnlessTheItemIsFetched() {
    assertFalse(store.completeExtraction(link, "T", null, null, emptyList(), poor = true))
    assertEquals(States.FETCHING, store.item(link)!!.state)
  }

  @Test fun aFetchedItemWithNoBodyBecomesInterrupted() {
    // Review Focus 3: never stuck in fetched.
    store.fetchSucceeded(link, "<html/>")
    store.bodyFile(link).delete()
    assertNull(store.bodyOrFail(link))
    val item = store.item(link)!!
    assertEquals(States.FETCH_FAILED, item.state)
    assertEquals("interrupted", item.failReason)
  }

  @Test fun bodyOrFailIsNullForAnItemNotFetched() {
    assertNull(store.bodyOrFail(link))
    assertEquals(States.FETCHING, store.item(link)!!.state)
  }

  @Test fun openedAtIsSetOnceAtTheFirstOpen() {
    store.markOpened(link, 5000)
    store.markOpened(link, 9000)
    assertEquals(5000L, store.item(link)!!.openedAt)
  }

  @Test fun cutsAreASeparateSetAndLeaveParagraphTextAlone() {
    val text = store.insertText("t", listOf("a", "b", "c"), 1000)
    store.setCut(text, 1, true)
    store.setCut(text, 1, true)
    store.setCut(text, 2, true)
    store.setCut(text, 2, false)
    assertEquals(listOf(1), store.cuts(text))
    assertEquals(listOf("a", "b", "c"), store.paragraphs(text).map { it.text })
  }

  @Test fun archiveKeepsTheStateAndRestoreClearsIt() {
    store.fetchSucceeded(link, "<html/>")
    store.completeExtraction(link, "T", null, null, listOf(ParagraphRow("p", "x")), poor = true)
    store.archive(link, 7000)
    assertEquals(7000L, store.item(link)!!.archivedAt)
    assertEquals(States.EXTRACT_POOR, store.item(link)!!.state)
    store.restore(link)
    assertNull(store.item(link)!!.archivedAt)
    assertEquals(States.EXTRACT_POOR, store.item(link)!!.state)
  }

  @Test fun deleteRemovesAWaitingBody() {
    store.fetchSucceeded(link, "<html/>")
    store.delete(link)
    assertFalse(store.bodyFile(link).exists())
  }
}
```

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.store.StoreLifecycleTest')`
Expected: FAIL to compile: "Unresolved reference 'fetchSucceeded'".

- [ ] **Step 2: Implement the lifecycle methods**

In `Store.kt`, add these methods to `class Store`, after `idsInState`:

```kotlin
  /** R-M03 to ADR 0007: the body is on disk before the state says it is. */
  fun fetchSucceeded(id: Long, html: String) {
    bodies.mkdirs()
    val tmp = File(bodies, "$id.html.tmp")
    tmp.writeText(html, Charsets.UTF_8)
    if (!tmp.renameTo(bodyFile(id))) throw IllegalStateException("body rename failed")
    setStateIf(id, States.FETCHING, States.FETCHED, null)
  }

  fun fetchFailed(id: Long, reason: String) {
    setStateIf(id, States.FETCHING, States.FETCH_FAILED, reason)
  }

  /** The user's Retry (R-M03 as amended): only a failed fetch goes back to fetching. */
  fun beginRetry(id: Long): Boolean = setStateIf(id, States.FETCH_FAILED, States.FETCHING, null)

  /**
   * The HTML of a fetched item, for JS extraction. A fetched item whose body is missing cannot
   * be extracted, so it becomes fetch-failed: interrupted instead of waiting forever.
   */
  fun bodyOrFail(id: Long): String? {
    if (item(id)?.state != States.FETCHED) return null
    val file = bodyFile(id)
    if (!file.exists()) {
      setStateIf(id, States.FETCHED, States.FETCH_FAILED, "interrupted")
      return null
    }
    return file.readText(Charsets.UTF_8)
  }

  /** R-M04: stores the extracted structure and discards the raw HTML. Only from fetched. */
  fun completeExtraction(
    id: Long,
    title: String,
    site: String?,
    byline: String?,
    paragraphs: List<ParagraphRow>,
    poor: Boolean,
  ): Boolean {
    val db = writableDatabase
    db.beginTransaction()
    try {
      val updated = db.update(
        "items",
        ContentValues().apply {
          put("title", title)
          put("site", site)
          put("byline", byline)
          put("state", if (poor) States.EXTRACT_POOR else States.READY)
          putNull("fail_reason")
        },
        "id = ? AND state = ?",
        arrayOf(id.toString(), States.FETCHED),
      )
      if (updated == 0) return false
      writeParagraphs(db, id, paragraphs)
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    bodyFile(id).delete()
    ItemEvents.changed()
    return true
  }

  /** ADR 0007: Trim opens automatically while openedAt is unset (R-M05). */
  fun markOpened(id: Long, now: Long) {
    writableDatabase.execSQL(
      "UPDATE items SET opened_at = ? WHERE id = ? AND opened_at IS NULL",
      arrayOf<Any>(now, id),
    )
    ItemEvents.changed()
  }

  /** R-M05, AGENTS.md 12: a cut is a row; the paragraph text is never touched. */
  fun setCut(id: Long, paragraphIndex: Int, cut: Boolean) {
    if (cut) {
      writableDatabase.execSQL(
        "INSERT OR IGNORE INTO cuts (item_id, paragraph_index) VALUES (?, ?)",
        arrayOf<Any>(id, paragraphIndex),
      )
    } else {
      writableDatabase.delete(
        "cuts",
        "item_id = ? AND paragraph_index = ?",
        arrayOf(id.toString(), paragraphIndex.toString()),
      )
    }
    ItemEvents.changed()
  }

  /** R-M11 as amended (ADR 0007): archive is a timestamp; the state is kept. */
  fun archive(id: Long, now: Long) {
    writableDatabase.execSQL("UPDATE items SET archived_at = ? WHERE id = ?", arrayOf<Any>(now, id))
    ItemEvents.changed()
  }

  fun restore(id: Long) {
    writableDatabase.execSQL("UPDATE items SET archived_at = NULL WHERE id = ?", arrayOf<Any>(id))
    ItemEvents.changed()
  }

  private fun setStateIf(id: Long, from: String, to: String, reason: String?): Boolean {
    val n = writableDatabase.update(
      "items",
      ContentValues().apply {
        put("state", to)
        if (reason == null) putNull("fail_reason") else put("fail_reason", reason)
      },
      "id = ? AND state = ?",
      arrayOf(id.toString(), from),
    )
    if (n > 0) ItemEvents.changed()
    return n > 0
  }
```

- [ ] **Step 3: Run both Store tests**

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.store.*')`
Expected: PASS, 18 tests (6 + 12).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/store/Store.kt \
  android/app/src/test/java/io/loopstring/readme/store/StoreLifecycleTest.kt
git commit -m "feat(store): item lifecycle: fetched body, failure, retry, extraction, cuts, archive

Refs REA-16"
```

---

### Task 4: `Fetcher`

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/fetch/Fetcher.kt`
- Test: `android/app/src/test/java/io/loopstring/readme/fetch/FetcherTest.kt`

**Interfaces:**
- Produces: `sealed class FetchResult { data class Ok(val html: String); data class Failed(val reason: String) }`; `class Fetcher(timeoutMs: Long = 20_000, capBytes: Long = Fetcher.CAP_BYTES) { fun fetch(url: String): FetchResult; val usesCache: Boolean }`; `Fetcher.CAP_BYTES = 5_242_880L`; `Fetcher.MAX_REDIRECTS = 5`.

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/io/loopstring/readme/fetch/FetcherTest.kt`:

```kotlin
package io.loopstring.readme.fetch

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// Plain JVM: Fetcher uses only OkHttp.
class FetcherTest {
  private lateinit var server: MockWebServer

  @Before fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After fun tearDown() = server.shutdown()

  private fun url(path: String) = server.url(path).toString()

  private fun redirect(to: String) =
    MockResponse().setResponseCode(302).setHeader("Location", to)

  @Test fun returnsTheBody() {
    server.enqueue(MockResponse().setBody("<html>hi</html>"))
    assertEquals(FetchResult.Ok("<html>hi</html>"), Fetcher().fetch(url("/a")))
  }

  @Test fun followsFiveRedirectsAndFailsTheSixth() {
    repeat(5) { server.enqueue(redirect("/r$it")) }
    server.enqueue(MockResponse().setBody("end"))
    assertEquals(FetchResult.Ok("end"), Fetcher().fetch(url("/start")))

    repeat(6) { server.enqueue(redirect("/s$it")) }
    server.enqueue(MockResponse().setBody("never"))
    assertEquals(FetchResult.Failed("too-many-redirects"), Fetcher().fetch(url("/start2")))
  }

  @Test fun aRedirectToANonHttpSchemeFails() {
    server.enqueue(redirect("ftp://example.com/file"))
    assertEquals(FetchResult.Failed("unsupported-redirect"), Fetcher().fetch(url("/a")))
  }

  @Test fun aCookieSetByOneHopIsNotSentOnTheNext() {
    // Review Focus 4.
    server.enqueue(redirect("/next").addHeader("Set-Cookie", "sid=secret; Path=/"))
    server.enqueue(MockResponse().setBody("ok"))
    Fetcher().fetch(url("/a"))
    server.takeRequest()
    assertNull(server.takeRequest().getHeader("Cookie"))
  }

  @Test fun anHttpErrorIsItsStatus() {
    server.enqueue(MockResponse().setResponseCode(404))
    assertEquals(FetchResult.Failed("http-404"), Fetcher().fetch(url("/a")))
  }

  @Test fun theCapIsEnforcedWhileStreamingWithoutContentLength() {
    val cap = 1000L
    server.enqueue(MockResponse().setChunkedBody("x".repeat(1001), 100))
    assertEquals(FetchResult.Failed("too-large"), Fetcher(capBytes = cap).fetch(url("/big")))
    server.enqueue(MockResponse().setChunkedBody("x".repeat(1000), 100))
    assertEquals(FetchResult.Ok("x".repeat(1000)), Fetcher(capBytes = cap).fetch(url("/exact")))
  }

  @Test fun theRealCapIsFiveMebibytes() {
    assertEquals(5L * 1024 * 1024, Fetcher.CAP_BYTES)
    server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(5 * 1024 * 1024 + 1))))
    assertEquals(FetchResult.Failed("too-large"), Fetcher().fetch(url("/huge")))
  }

  @Test fun theDeadlineCoversEveryHop() {
    // Each hop is fast enough alone; together they pass the deadline.
    repeat(3) {
      server.enqueue(redirect("/h$it").setHeadersDelay(300, TimeUnit.MILLISECONDS))
    }
    server.enqueue(MockResponse().setBody("late"))
    assertEquals(FetchResult.Failed("timeout"), Fetcher(timeoutMs = 700).fetch(url("/a")))
  }

  @Test fun anUnreachableHostIsOffline() {
    val dead = MockWebServer()
    dead.start()
    val gone = dead.url("/x").toString()
    dead.shutdown()
    assertEquals(FetchResult.Failed("offline"), Fetcher().fetch(gone))
  }

  @Test fun aMalformedUrlIsBadUrl() {
    assertEquals(FetchResult.Failed("bad-url"), Fetcher().fetch("https://"))
  }

  @Test fun thereIsNoCacheAndNoCookieJar() {
    assertFalse(Fetcher().usesCache)
  }
}
```

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.fetch.FetcherTest')`
Expected: FAIL to compile: "Unresolved reference 'Fetcher'".

- [ ] **Step 2: Implement `Fetcher`**

`android/app/src/main/java/io/loopstring/readme/fetch/Fetcher.kt`:

```kotlin
package io.loopstring.readme.fetch

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer

sealed class FetchResult {
  data class Ok(val html: String) : FetchResult()

  /** `reason` is a class (R-M10), never built from the URL. */
  data class Failed(val reason: String) : FetchResult()
}

/**
 * R-M03, AGENTS.md 6-7: the only outbound network call site in the app. One user request is
 * one GET of the shared URL plus at most 5 manually followed http(s) redirects, inside one
 * 20 s deadline. No cookies, no cache, and a 5 MB cap enforced while streaming. Nothing here
 * logs; the caller logs ids and reason classes only.
 */
class Fetcher(
  private val timeoutMs: Long = 20_000,
  private val capBytes: Long = CAP_BYTES,
) {
  private val client = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .cache(null)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  val usesCache: Boolean get() = client.cache != null || client.cookieJar != CookieJar.NO_COOKIES

  fun fetch(url: String): FetchResult {
    var target: HttpUrl = url.toHttpUrlOrNull() ?: return FetchResult.Failed("bad-url")
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var redirects = 0
    while (true) {
      val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
      if (remainingMs <= 0) return FetchResult.Failed("timeout")
      val call = client.newCall(
        Request.Builder()
          .url(target)
          .header("User-Agent", USER_AGENT)
          .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
          .get()
          .build(),
      )
      // One deadline across all hops (R-M03: a call timeout, not per read).
      call.timeout().timeout(remainingMs, TimeUnit.MILLISECONDS)
      try {
        val response = call.execute()
        try {
          if (response.isRedirect) {
            val location = response.header("Location")
              ?: return FetchResult.Failed("http-${response.code}")
            // HttpUrl only resolves http and https; anything else comes back null.
            val next = response.request.url.resolve(location)
              ?: return FetchResult.Failed("unsupported-redirect")
            redirects++
            if (redirects > MAX_REDIRECTS) return FetchResult.Failed("too-many-redirects")
            target = next
            continue
          }
          if (!response.isSuccessful) return FetchResult.Failed("http-${response.code}")
          val body = response.body ?: return FetchResult.Failed("network")
          return readCapped(body)?.let { FetchResult.Ok(it) } ?: FetchResult.Failed("too-large")
        } finally {
          response.close()
        }
      } catch (e: InterruptedIOException) {
        return FetchResult.Failed("timeout")
      } catch (e: UnknownHostException) {
        return FetchResult.Failed("offline")
      } catch (e: ConnectException) {
        return FetchResult.Failed("offline")
      } catch (e: NoRouteToHostException) {
        return FetchResult.Failed("offline")
      } catch (e: IOException) {
        return FetchResult.Failed("network")
      }
    }
  }

  /** Reads at most capBytes + 1 bytes; null when the body is larger than the cap. */
  private fun readCapped(body: ResponseBody): String? {
    val source = body.source()
    val buffer = Buffer()
    while (buffer.size <= capBytes) {
      val want = minOf(CHUNK, capBytes + 1 - buffer.size)
      if (source.read(buffer, want) == -1L) break
    }
    if (buffer.size > capBytes) return null
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return buffer.readString(charset)
  }

  companion object {
    const val CAP_BYTES = 5L * 1024 * 1024
    const val MAX_REDIRECTS = 5
    private const val CHUNK = 64L * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ReadMe/1.0"
  }
}
```

- [ ] **Step 3: Run the test**

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.fetch.FetcherTest')`
Expected: PASS, 11 tests.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/fetch/Fetcher.kt \
  android/app/src/test/java/io/loopstring/readme/fetch/FetcherTest.kt
git commit -m "feat(fetcher): one GET with manual redirects, one deadline, no cookies, streaming cap

Refs REA-16"
```

---

### Task 5: `FetchWorker`, `Recovery` and `ShareActivity`

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/fetch/FetchWorker.kt`
- Create: `android/app/src/main/java/io/loopstring/readme/fetch/Recovery.kt`
- Create: `android/app/src/main/java/io/loopstring/readme/ShareActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/io/loopstring/readme/MainApplication.kt`
- Test: `android/app/src/test/java/io/loopstring/readme/fetch/FetchWorkerTest.kt`, `RecoveryTest.kt`, `android/app/src/test/java/io/loopstring/readme/ShareActivityTest.kt`

**Interfaces:**
- Consumes: `Fetcher`, `FetchResult` (Task 4); `Store`, `States` (Tasks 2-3); `Intake` (Task 1).
- Produces: `FetchWorker.enqueue(context, id: Long)`, `FetchWorker.uniqueName(id: Long): String`, `Recovery.run(context)`, and `ShareActivity` registered for `ACTION_SEND text/plain`.

- [ ] **Step 1: Write the failing tests**

`android/app/src/test/java/io/loopstring/readme/fetch/FetchWorkerTest.kt`:

```kotlin
package io.loopstring.readme.fetch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FetchWorkerTest {
  private lateinit var ctx: Context
  private lateinit var server: MockWebServer

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    server = MockWebServer()
    server.start()
  }

  @After fun tearDown() {
    server.shutdown()
    Store.resetForTest()
  }

  private fun run(id: Long): ListenableWorker.Result =
    TestListenableWorkerBuilder<FetchWorker>(ctx)
      .setInputData(workDataOf(FetchWorker.KEY_ID to id))
      .build()
      .doWork()

  @Test fun aSuccessfulFetchLeavesTheItemFetchedWithItsBody() {
    server.enqueue(MockResponse().setBody("<html>page</html>"))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    assertTrue(run(id) is ListenableWorker.Result.Success)
    assertEquals(States.FETCHED, store.item(id)!!.state)
    assertEquals("<html>page</html>", store.bodyOrFail(id))
  }

  @Test fun aFailedFetchIsVisibleWithItsReasonAndNotRetried() {
    server.enqueue(MockResponse().setResponseCode(500))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    assertTrue(run(id) is ListenableWorker.Result.Success)
    assertEquals("http-500", store.item(id)!!.failReason)
    assertEquals(1, server.requestCount)
  }

  @Test fun anItemNoLongerFetchingIsLeftAlone() {
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    store.fetchFailed(id, "offline")
    run(id)
    assertEquals(0, server.requestCount)
  }
}
```

`android/app/src/test/java/io/loopstring/readme/fetch/RecoveryTest.kt`:

```kotlin
package io.loopstring.readme.fetch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Workers never run here: a queued request stays queued, and no test reaches the network.
private val NEVER = Executor { }

@RunWith(RobolectricTestRunner::class)
class RecoveryTest {
  private lateinit var ctx: Context

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      ctx,
      Configuration.Builder().setExecutor(NEVER).build(),
    )
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aFetchingItemWithNoWorkIsInterrupted() {
    val store = Store.get(ctx)
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    Recovery.run(ctx)
    assertEquals(States.FETCH_FAILED, store.item(id)!!.state)
    assertEquals("interrupted", store.item(id)!!.failReason)
  }

  @Test fun aFetchingItemWhoseWorkIsStillQueuedIsLeftFetching() {
    // Review Focus 1: the process died, WorkManager will run the work again.
    val store = Store.get(ctx)
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    FetchWorker.enqueue(ctx, id) // queued; the NEVER executor keeps it from running
    Recovery.run(ctx)
    assertEquals(States.FETCHING, store.item(id)!!.state)
  }

  @Test fun otherStatesAreUntouched() {
    val store = Store.get(ctx)
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    store.fetchSucceeded(id, "<html/>")
    Recovery.run(ctx)
    assertEquals(States.FETCHED, store.item(id)!!.state)
  }
}
```

`android/app/src/test/java/io/loopstring/readme/ShareActivityTest.kt`:

```kotlin
package io.loopstring.readme

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

// Workers never run here: the queued request stays queued, and no test reaches the network.
private val NEVER = Executor { }

@RunWith(RobolectricTestRunner::class)
class ShareActivityTest {
  private lateinit var ctx: Context

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      ctx,
      Configuration.Builder().setExecutor(NEVER).build(),
    )
  }

  @After fun tearDown() = Store.resetForTest()

  private fun share(text: String?): ShareActivity {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
    if (text != null) intent.putExtra(Intent.EXTRA_TEXT, text)
    return Robolectric.buildActivity(ShareActivity::class.java, intent).create().get()
  }

  @Test fun aSharedLinkIsStoredFetchingAndItsWorkIsQueued() {
    val activity = share("Some title https://www.example.com/story?id=7")
    val item = Store.get(ctx).items().single()
    assertEquals("link", item.kind)
    assertEquals("https://www.example.com/story?id=7", item.url)
    assertEquals("example.com", item.title)
    assertEquals(States.FETCHING, item.state)
    val work = WorkManager.getInstance(ctx)
      .getWorkInfosForUniqueWork(FetchWorker.uniqueName(item.id)).get()
    assertEquals(1, work.size)
    assertTrue(!work.single().state.isFinished)
    assertTrue(activity.isFinishing)
  }

  @Test fun sharedTextIsAReadyTextItem() {
    share("First paragraph here.\n\nSecond one.")
    val store = Store.get(ctx)
    val item = store.items().single()
    assertEquals("text", item.kind)
    assertEquals(States.READY, item.state)
    assertEquals("First paragraph here.", item.title)
    assertEquals(listOf("First paragraph here.", "Second one."), store.paragraphs(item.id).map { it.text })
  }

  @Test fun anEmptyShareStoresNothing() {
    val activity = share("   ")
    assertTrue(Store.get(ctx).items().isEmpty())
    assertTrue(activity.isFinishing)
  }

  @Test fun aShareWithNoTextStoresNothing() {
    share(null)
    assertTrue(Store.get(ctx).items().isEmpty())
  }
}
```

Run: `(cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.fetch.*' --tests 'io.loopstring.readme.ShareActivityTest')`
Expected: FAIL to compile: "Unresolved reference 'FetchWorker'" (and `Recovery`, `ShareActivity`).

- [ ] **Step 2: Implement `FetchWorker`**

`android/app/src/main/java/io/loopstring/readme/fetch/FetchWorker.kt`:

```kotlin
package io.loopstring.readme.fetch

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import java.util.concurrent.TimeUnit

/**
 * R-M02/R-M03: the request runs in WorkManager, so it survives the share activity finishing.
 * One run per user request: a failure is stored for the user's Retry and never retried
 * automatically. If the process dies mid-request WorkManager runs it again, which is the same
 * request (its first GET never completed). No network constraint: an offline share fails at
 * once as "offline", visible, instead of waiting.
 */
class FetchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
  override fun doWork(): Result {
    val id = inputData.getLong(KEY_ID, -1)
    val store = Store.get(applicationContext)
    val item = store.item(id) ?: return Result.success()
    val url = item.url
    if (item.state != States.FETCHING || url == null) return Result.success()
    val started = System.nanoTime()
    val outcome = when (val r = Fetcher().fetch(url)) {
      is FetchResult.Ok -> {
        store.fetchSucceeded(id, r.html)
        "ok"
      }
      is FetchResult.Failed -> {
        store.fetchFailed(id, r.reason)
        r.reason
      }
    }
    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    // AGENTS.md 1: an id, a reason class and a duration; never the URL.
    Log.i(TAG, "request id=$id outcome=$outcome ms=$ms")
    return Result.success()
  }

  companion object {
    const val KEY_ID = "itemId"
    private const val TAG = "ReadMe"

    fun uniqueName(id: Long) = "fetch-$id"

    fun enqueue(context: Context, id: Long) {
      WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueName(id),
        // KEEP: a second enqueue while one is pending is the same request.
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<FetchWorker>().setInputData(workDataOf(KEY_ID to id)).build(),
      )
    }
  }
}
```

- [ ] **Step 3: Implement `Recovery`**

`android/app/src/main/java/io/loopstring/readme/fetch/Recovery.kt`:

```kotlin
package io.loopstring.readme.fetch

import android.content.Context
import androidx.work.WorkManager
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store

/**
 * R-M02 recovery, run at every process start: a fetching item whose work is gone (finished or
 * never recorded) can never leave fetching on its own, so it becomes fetch-failed: interrupted.
 * Work still queued or running is left alone (WorkManager re-runs it). Fetched items wait for
 * JS extraction (ADR 0007) and are not touched.
 */
object Recovery {
  fun run(context: Context) {
    val store = Store.get(context)
    val work = WorkManager.getInstance(context)
    for (id in store.idsInState(States.FETCHING)) {
      val alive = work.getWorkInfosForUniqueWork(FetchWorker.uniqueName(id)).get()
        .any { !it.state.isFinished }
      if (!alive) store.fetchFailed(id, "interrupted")
    }
  }
}
```

- [ ] **Step 4: Implement `ShareActivity`**

`android/app/src/main/java/io/loopstring/readme/ShareActivity.kt`:

```kotlin
package io.loopstring.readme

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.intake.Intake
import io.loopstring.readme.store.Store

/**
 * R-M02: the share target. Classifies and stores the item, queues the request for a link, and
 * finishes at once so the user stays in the app they shared from. No JS runtime is started.
 */
class ShareActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val shared = intent
      ?.takeIf { it.action == Intent.ACTION_SEND }
      ?.getCharSequenceExtra(Intent.EXTRA_TEXT)
      ?.toString()
      .orEmpty()
    val store = Store.get(this)
    val now = System.currentTimeMillis()
    val saved = when (val share = Intake.classify(shared)) {
      is Intake.Share.Link -> {
        val id = store.insertLink(share.url, Intake.hostTitle(share.url), now)
        FetchWorker.enqueue(this, id)
        true
      }
      is Intake.Share.Text -> {
        val paragraphs = Intake.splitParagraphs(share.text)
        store.insertText(Intake.textTitle(paragraphs), paragraphs, now)
        true
      }
      Intake.Share.Empty -> false
    }
    Toast.makeText(
      this,
      if (saved) "Saved to Read Me" else "Nothing to save",
      Toast.LENGTH_SHORT,
    ).show()
    finish()
  }
}
```

- [ ] **Step 5: Register the activity and run recovery at start**

In `android/app/src/main/AndroidManifest.xml`, after the `MainActivity` `</activity>`:

```xml
      <!-- R-M02: share target. Translucent and finishes at once; no JS runtime is started. -->
      <activity
        android:name=".ShareActivity"
        android:exported="true"
        android:excludeFromRecents="true"
        android:noHistory="true"
        android:theme="@android:style/Theme.Translucent.NoTitleBar">
        <intent-filter>
            <action android:name="android.intent.action.SEND" />
            <category android:name="android.intent.category.DEFAULT" />
            <data android:mimeType="text/plain" />
        </intent-filter>
      </activity>
```

In `MainApplication.kt`, add `import android.util.Log` and `import io.loopstring.readme.fetch.Recovery`, then make `onCreate`:

```kotlin
  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)
    // R-M02 recovery at every process start, off the main thread (WorkManager queries block).
    Thread {
      try {
        Recovery.run(this)
      } catch (t: Throwable) {
        // AGENTS.md 1: the exception's class only; its message could carry a URL.
        Log.w("ReadMe", "recovery failed: ${t.javaClass.simpleName}")
      }
    }.start()
  }
```

- [ ] **Step 6: Run the tests**

Run: `(cd android && ./gradlew -q testDebugUnitTest)`
Expected: PASS, all Kotlin tests (4 + 6 + 12 + 11 + 3 + 3 + 4 = 43).

- [ ] **Step 7: Build, then commit**

Run: `npm run -s build:release`
Expected: `built <hash> (dirty)`. The merged manifest accepts `ShareActivity`.

```bash
git add android/app/src/main android/app/src/test
git commit -m "feat(share): share activity, WorkManager request job and start-up recovery

Refs REA-16"
```

---

### Task 6: The `ReadMeSpeech` TurboModule, the TS `library` facade, and the list screen

**Files:**
- Modify: `package.json` (`codegenConfig`)
- Create: `src/native/NativeReadMeSpeech.ts`
- Create: `android/app/src/main/java/io/loopstring/readme/ReadMeSpeechModule.kt`, `ReadMeSpeechPackage.kt`
- Modify: `android/app/src/main/java/io/loopstring/readme/MainApplication.kt`
- Create: `src/library/library.ts`
- Modify: `App.tsx`, `__tests__/App.test.tsx`
- Test: `__tests__/library.test.ts`

**Interfaces:**
- Consumes:
  - `Store` methods (Tasks 2-3), `FetchWorker.enqueue` (Task 5), `ItemEvents` (Task 2).
  - `extractArticle(html, url?) => { title, site?, byline?, paragraphs, poor }` (Phase 1).
- Produces:
  - The TurboModule `ReadMeSpeech`, which emits the device event `ReadMeItemsChanged`.
  - From `src/library/library.ts`:
    - types `Item`, `ItemState`, `ItemDetail`;
    - `listItems(): Promise<Item[]>`, `getItem(id): Promise<ItemDetail | null>`;
    - `drainFetched(extract?): Promise<number>`;
    - `retryFetch(id)`, `deleteItem(id)`, `markOpened(id)`, `setCut(id, paragraphIndex, cut)`, `archiveItem(id)`, `restoreItem(id)`;
    - `onItemsChanged(cb): () => void`.

- [ ] **Step 1: The codegen spec**

Add to `package.json`, top level:

```json
  "codegenConfig": {
    "name": "ReadMeSpec",
    "type": "modules",
    "jsSrcsDir": "src/native",
    "android": { "javaPackageName": "io.loopstring.readme.spec" }
  }
```

`src/native/NativeReadMeSpeech.ts`:

```ts
// The ReadMeSpeech TurboModule (ADR 0001): JS reads and writes the library only through it.
// Codegen reads this file (package.json codegenConfig). Ids are numbers on this side.
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NativeItem = {
  id: number;
  kind: string;
  url: string | null;
  title: string;
  site: string | null;
  byline: string | null;
  createdAt: number;
  state: string;
  failReason: string | null;
  openedAt: number | null;
  archivedAt: number | null;
};

export type NativeParagraph = { kind: string; text: string };

export type NativeItemDetail = {
  item: NativeItem;
  paragraphs: NativeParagraph[];
  cuts: number[];
};

export interface Spec extends TurboModule {
  listItems(): Promise<NativeItem[]>;
  getItem(id: number): Promise<NativeItemDetail | null>;
  getBody(id: number): Promise<string | null>;
  completeExtraction(
    id: number,
    title: string,
    site: string | null,
    byline: string | null,
    paragraphs: NativeParagraph[],
    poor: boolean,
  ): Promise<boolean>;
  retryFetch(id: number): Promise<boolean>;
  deleteItem(id: number): Promise<void>;
  markOpened(id: number): Promise<void>;
  setCut(id: number, paragraphIndex: number, cut: boolean): Promise<void>;
  archiveItem(id: number): Promise<void>;
  restoreItem(id: number): Promise<void>;
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('ReadMeSpeech');
```

- [ ] **Step 2: Write the failing library test**

`__tests__/library.test.ts`:

```ts
import type {
  NativeItem,
  NativeParagraph,
} from '../src/native/NativeReadMeSpeech';

// jest.mock factories may only reference variables whose names start with "mock".
type Row = NativeItem & { body?: string | null };
const mockRows: Row[] = [];
const mockCompleted: {
  id: number;
  title: string;
  paragraphs: NativeParagraph[];
  poor: boolean;
}[] = [];

jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    listItems: jest.fn(async () => mockRows.map(({ body: _b, ...r }) => ({ ...r }))),
    getBody: jest.fn(async (id: number) => mockRows.find(r => r.id === id)?.body ?? null),
    completeExtraction: jest.fn(
      async (
        id: number,
        title: string,
        _site: string | null,
        _byline: string | null,
        paragraphs: NativeParagraph[],
        poor: boolean,
      ) => {
        mockCompleted.push({ id, title, paragraphs, poor });
        const r = mockRows.find(x => x.id === id);
        if (r) r.state = poor ? 'extract-poor' : 'ready';
        return true;
      },
    ),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

import { drainFetched, listItems } from '../src/library/library';

const row = (id: number, state: string, body: string | null = null): Row => ({
  id,
  kind: 'link',
  url: `https://example.com/${id}`,
  title: 'example.com',
  site: null,
  byline: null,
  createdAt: id,
  state,
  failReason: null,
  openedAt: null,
  archivedAt: null,
  body,
});

const fakeExtract = (html: string) => ({
  title: `T:${html}`,
  paragraphs: [{ kind: 'p' as const, text: html }],
  poor: html === 'thin',
});

beforeEach(() => {
  mockRows.length = 0;
  mockCompleted.length = 0;
});

test('listItems maps native nulls to absent fields', async () => {
  mockRows.push(row(1, 'ready'));
  const [item] = await listItems();
  expect(item.site).toBeUndefined();
  expect(item.failReason).toBeUndefined();
  expect(item.url).toBe('https://example.com/1');
});

test('drain extracts only fetched items and records poor ones', async () => {
  mockRows.push(row(1, 'fetched', 'page'), row(2, 'fetching'), row(3, 'fetched', 'thin'));
  expect(await drainFetched(fakeExtract)).toBe(2);
  expect(mockCompleted.map(c => [c.id, c.poor])).toEqual([
    [1, false],
    [3, true],
  ]);
  expect(mockCompleted[0].title).toBe('T:page');
});

test('a fetched item whose body is gone is skipped (the native side fails it)', async () => {
  mockRows.push(row(1, 'fetched', null));
  expect(await drainFetched(fakeExtract)).toBe(0);
});

test('an extractor that throws still completes the item as poor', async () => {
  mockRows.push(row(1, 'fetched', 'page'));
  const boom = () => {
    throw new Error('x');
  };
  expect(await drainFetched(boom)).toBe(1);
  expect(mockCompleted[0].poor).toBe(true);
  expect(mockCompleted[0].paragraphs).toEqual([]);
});

test('a second drain request during a drain runs again (Review Focus 2)', async () => {
  mockRows.push(row(1, 'fetched', 'page'));
  const first = drainFetched(fakeExtract);
  mockRows.push(row(2, 'fetched', 'late')); // completes while the first drain runs
  const second = drainFetched(fakeExtract);
  expect(await first).toBe(2);
  expect(await second).toBe(2);
  expect(mockCompleted.map(c => c.id).sort()).toEqual([1, 2]);
});
```

Run: `npx jest __tests__/library.test.ts`
Expected: FAIL: "Cannot find module '../src/library/library'".

- [ ] **Step 3: Implement the facade**

`src/library/library.ts`:

```ts
// The TS library facade (ADR 0001): every read and write goes through ReadMeSpeech; JS never
// holds the database. drainFetched is ADR 0007's JS half: items the native side stored as
// "fetched" are extracted here (Phase 1 extract) and handed back. Never logs.
import { NativeEventEmitter } from 'react-native';
import Native, {
  type NativeItem,
  type NativeParagraph,
} from '../native/NativeReadMeSpeech';
import { extractArticle, type Extracted } from '../extract/extract';
import type { Paragraph, ParagraphKind } from '../types';

export type ItemState =
  | 'fetching'
  | 'fetched'
  | 'fetch-failed'
  | 'extract-poor'
  | 'ready';

export type Item = {
  id: number;
  kind: 'link' | 'text';
  url?: string;
  title: string;
  site?: string;
  byline?: string;
  createdAt: number;
  state: ItemState;
  failReason?: string;
  openedAt?: number;
  archivedAt?: number;
};

export type ItemDetail = { item: Item; paragraphs: Paragraph[]; cuts: number[] };

type Extract = (html: string, url?: string) => Pick<
  Extracted,
  'title' | 'paragraphs' | 'poor'
> &
  Partial<Pick<Extracted, 'site' | 'byline'>>;

const opt = <T>(v: T | null): T | undefined => (v === null ? undefined : v);

export function toItem(n: NativeItem): Item {
  return {
    id: n.id,
    kind: n.kind as Item['kind'],
    url: opt(n.url),
    title: n.title,
    site: opt(n.site),
    byline: opt(n.byline),
    createdAt: n.createdAt,
    state: n.state as ItemState,
    failReason: opt(n.failReason),
    openedAt: opt(n.openedAt),
    archivedAt: opt(n.archivedAt),
  };
}

export async function listItems(): Promise<Item[]> {
  return (await Native.listItems()).map(toItem);
}

export async function getItem(id: number): Promise<ItemDetail | null> {
  const d = await Native.getItem(id);
  if (d === null) return null;
  return {
    item: toItem(d.item),
    paragraphs: d.paragraphs.map(p => ({
      kind: p.kind as ParagraphKind,
      text: p.text,
    })),
    cuts: d.cuts,
  };
}

async function drainOnce(extract: Extract): Promise<number> {
  let n = 0;
  for (const item of await listItems()) {
    if (item.state !== 'fetched') continue;
    // Null when the body is gone; the native side has already failed the item.
    const html = await Native.getBody(item.id);
    if (html === null) continue;
    let ex: ReturnType<Extract>;
    try {
      ex = extract(html, item.url);
    } catch {
      ex = { title: '', paragraphs: [], poor: true };
    }
    const paragraphs: NativeParagraph[] = ex.paragraphs.map(p => ({
      kind: p.kind,
      text: p.text,
    }));
    await Native.completeExtraction(
      item.id,
      ex.title || item.title,
      ex.site ?? null,
      ex.byline ?? null,
      paragraphs,
      ex.poor,
    );
    n++;
  }
  return n;
}

let running: Promise<number> | null = null;
let again = false;

/**
 * Extracts every fetched item. A call while a drain runs makes that drain go round again, so
 * an item fetched mid-drain is not left until the next app start (Review Focus 2).
 */
export function drainFetched(extract: Extract = extractArticle): Promise<number> {
  if (running) {
    again = true;
    return running;
  }
  running = (async () => {
    let total = 0;
    do {
      again = false;
      total += await drainOnce(extract);
    } while (again);
    return total;
  })().finally(() => {
    running = null;
  });
  return running;
}

export const retryFetch = (id: number) => Native.retryFetch(id);
export const deleteItem = (id: number) => Native.deleteItem(id);
export const markOpened = (id: number) => Native.markOpened(id);
export const setCut = (id: number, paragraphIndex: number, cut: boolean) =>
  Native.setCut(id, paragraphIndex, cut);
export const archiveItem = (id: number) => Native.archiveItem(id);
export const restoreItem = (id: number) => Native.restoreItem(id);

export function onItemsChanged(cb: () => void): () => void {
  const sub = new NativeEventEmitter(Native).addListener('ReadMeItemsChanged', cb);
  return () => sub.remove();
}
```

Run: `npx jest __tests__/library.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 4: Implement the Kotlin module and package**

`android/app/src/main/java/io/loopstring/readme/ReadMeSpeechModule.kt`:

```kotlin
package io.loopstring.readme

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.spec.NativeReadMeSpeechSpec
import io.loopstring.readme.store.ItemEvents
import io.loopstring.readme.store.ItemRow
import io.loopstring.readme.store.ParagraphRow
import io.loopstring.readme.store.Store

/**
 * ADR 0001: the only way JS reaches the library. A view, not a driver: every write lands in
 * the Store, which fires ItemEvents; this module forwards that as "ReadMeItemsChanged" while
 * JS is alive. A failure rejects with the exception's class only (AGENTS.md 1).
 */
class ReadMeSpeechModule(ctx: ReactApplicationContext) : NativeReadMeSpeechSpec(ctx) {
  private val store get() = Store.get(reactApplicationContext)
  private val onChange: () -> Unit = { reactApplicationContext.emitDeviceEvent(EVENT_CHANGED) }

  override fun getName(): String = NAME

  override fun initialize() {
    super.initialize()
    ItemEvents.add(onChange)
  }

  override fun invalidate() {
    ItemEvents.remove(onChange)
    super.invalidate()
  }

  override fun listItems(promise: Promise) = settle(promise) {
    Arguments.createArray().apply { store.items().forEach { pushMap(it.toMap()) } }
  }

  override fun getItem(id: Double, promise: Promise) = settle(promise) {
    val item = store.item(id.toLong()) ?: return@settle null
    Arguments.createMap().apply {
      putMap("item", item.toMap())
      putArray(
        "paragraphs",
        Arguments.createArray().apply {
          store.paragraphs(item.id).forEach { p ->
            pushMap(Arguments.createMap().apply {
              putString("kind", p.kind)
              putString("text", p.text)
            })
          }
        },
      )
      putArray(
        "cuts",
        Arguments.createArray().apply { store.cuts(item.id).forEach { pushInt(it) } },
      )
    }
  }

  override fun getBody(id: Double, promise: Promise) = settle(promise) { store.bodyOrFail(id.toLong()) }

  override fun completeExtraction(
    id: Double,
    title: String,
    site: String?,
    byline: String?,
    paragraphs: ReadableArray,
    poor: Boolean,
    promise: Promise,
  ) = settle(promise) {
    val rows = (0 until paragraphs.size()).map { i ->
      val p = paragraphs.getMap(i)!!
      ParagraphRow(p.getString("kind")!!, p.getString("text")!!)
    }
    store.completeExtraction(id.toLong(), title, site, byline, rows, poor)
  }

  override fun retryFetch(id: Double, promise: Promise) = settle(promise) {
    val started = store.beginRetry(id.toLong())
    if (started) FetchWorker.enqueue(reactApplicationContext, id.toLong())
    started
  }

  override fun deleteItem(id: Double, promise: Promise) = settle(promise) { store.delete(id.toLong()); null }

  override fun markOpened(id: Double, promise: Promise) =
    settle(promise) { store.markOpened(id.toLong(), System.currentTimeMillis()); null }

  override fun setCut(id: Double, paragraphIndex: Double, cut: Boolean, promise: Promise) =
    settle(promise) { store.setCut(id.toLong(), paragraphIndex.toInt(), cut); null }

  override fun archiveItem(id: Double, promise: Promise) =
    settle(promise) { store.archive(id.toLong(), System.currentTimeMillis()); null }

  override fun restoreItem(id: Double, promise: Promise) = settle(promise) { store.restore(id.toLong()); null }

  // NativeEventEmitter's contract; the events go out through emitDeviceEvent.
  override fun addListener(eventName: String) {}

  override fun removeListeners(count: Double) {}

  private inline fun settle(promise: Promise, block: () -> Any?) {
    try {
      promise.resolve(block())
    } catch (t: Throwable) {
      promise.reject("E_LIBRARY", "library operation failed: ${t.javaClass.simpleName}")
    }
  }

  private fun ItemRow.toMap(): WritableMap = Arguments.createMap().apply {
    putDouble("id", id.toDouble())
    putString("kind", kind)
    putString("url", url)
    putString("title", title)
    putString("site", site)
    putString("byline", byline)
    putDouble("createdAt", createdAt.toDouble())
    putString("state", state)
    putString("failReason", failReason)
    if (openedAt == null) putNull("openedAt") else putDouble("openedAt", openedAt.toDouble())
    if (archivedAt == null) putNull("archivedAt") else putDouble("archivedAt", archivedAt.toDouble())
  }

  companion object {
    const val NAME = "ReadMeSpeech"
    const val EVENT_CHANGED = "ReadMeItemsChanged"
  }
}
```

`android/app/src/main/java/io/loopstring/readme/ReadMeSpeechPackage.kt`:

```kotlin
package io.loopstring.readme

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class ReadMeSpeechPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == ReadMeSpeechModule.NAME) ReadMeSpeechModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      ReadMeSpeechModule.NAME to ReactModuleInfo(
        ReadMeSpeechModule.NAME,
        ReadMeSpeechModule.NAME,
        false, // canOverrideExistingModule
        false, // needsEagerInit
        false, // isCxxModule
        true, // isTurboModule
      ),
    )
  }
}
```

In `MainApplication.kt`, replace the comment line `// add(MyReactNativePackage())` with `add(ReadMeSpeechPackage())`.

- [ ] **Step 5: The list screen**

Replace `App.tsx` with:

```tsx
// A plain list of items and their states, so Phase 2 can be seen and checked on the phone.
// The real list, Trim and Reader screens are Phase 4 (R-M01, R-M10).
import React, { useCallback, useEffect, useState } from 'react';
import { FlatList, StatusBar, StyleSheet, Text, View } from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import {
  drainFetched,
  listItems,
  onItemsChanged,
  type Item,
} from './src/library/library';

function Library() {
  const insets = useSafeAreaInsets();
  const [items, setItems] = useState<Item[]>([]);

  const refresh = useCallback(() => {
    listItems().then(setItems, () => setItems([]));
  }, []);

  useEffect(() => {
    refresh();
    drainFetched().catch(() => undefined);
    return onItemsChanged(() => {
      refresh();
      drainFetched().catch(() => undefined);
    });
  }, [refresh]);

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      <FlatList
        data={items}
        keyExtractor={item => String(item.id)}
        ListEmptyComponent={<Text style={styles.empty}>Share a link or text to Read Me.</Text>}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <Text style={styles.title}>{item.title}</Text>
            <Text style={styles.state}>
              {item.state}
              {item.failReason ? `: ${item.failReason}` : ''}
            </Text>
          </View>
        )}
      />
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="default" />
      <Library />
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  empty: { padding: 24 },
  row: { paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 16 },
  state: { fontSize: 13, opacity: 0.7 },
});
```

In `__tests__/App.test.tsx`, add above `import App from '../App';`:

```tsx
jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    listItems: jest.fn(async () => []),
    getBody: jest.fn(async () => null),
    completeExtraction: jest.fn(async () => true),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));
```

- [ ] **Step 6: Run the JS gates**

Run: `npm test && npm run typecheck && npm run lint`
Expected: all pass. `networkGuard` still passes, because the spec and library name no JS network API.

- [ ] **Step 7: Build**

Run: `npm run -s build:release`
Expected: `built <hash> (dirty)`, and `NativeReadMeSpeechSpec.java` is generated under `android/app/build/generated/source/codegen/java/io/loopstring/readme/spec/`.

- [ ] **Step 8: Commit**

```bash
git add package.json src/native src/library App.tsx __tests__/App.test.tsx __tests__/library.test.ts \
  android/app/src/main/java/io/loopstring/readme
git commit -m "feat(library): ReadMeSpeech TurboModule, TS facade with fetched drain, plain list

Refs REA-16"
```

- [ ] **Step 9: First device check of the module**

Run: `npm run -s build:release && npm run -s device:smoke`
Expected: `device:smoke PASS`, and on the phone the list shows "Share a link or text to Read Me." That proves the TurboModule loads and `listItems` resolves on Hermes; a missing module throws at `getEnforcing` and the smoke check sees a crash. If it fails, read `adb logcat -d -s ReactNativeJS:V AndroidRuntime:E` (never paste item text) and fix before going on.

---

### Task 7: Network guards: the release runtime stub and the Kotlin outbound rule

**Files:**
- Create: `src/net/guard.ts`, `src/net/installGuard.ts`
- Modify: `index.js`
- Modify: `__tests__/networkGuard.test.ts`
- Test: `__tests__/guard.test.ts`

**Interfaces:**
- Produces: `installRuntimeGuard(target: Record<string, unknown>): void`; `GUARDED_NAMES`.

- [ ] **Step 1: Write the failing tests**

`__tests__/guard.test.ts`:

```ts
import { GUARDED_NAMES, installRuntimeGuard } from '../src/net/guard';

test('each JS network API throws once the guard is installed, and stays replaced', () => {
  const target: Record<string, unknown> = {
    [GUARDED_NAMES[0]]: () => 'real',
    [GUARDED_NAMES[1]]: class {},
    [GUARDED_NAMES[2]]: class {},
  };
  installRuntimeGuard(target);
  for (const name of GUARDED_NAMES) {
    const api = target[name] as (...a: unknown[]) => unknown;
    expect(() => api('https://example.com')).toThrow('network access is disabled');
    expect(() => new (api as unknown as new () => unknown)()).toThrow(
      'network access is disabled',
    );
    expect(() => {
      target[name] = () => 'again';
    }).toThrow(TypeError);
  }
});
```

Append to `__tests__/networkGuard.test.ts`:

```ts
// R-M09.2 as amended (F16), Kotlin half: only Fetcher makes an outbound connection, and a
// ServerSocket is allowed only in BridgeServer (Phase 5).
const KOTLIN_ROOT = join(ROOT, 'android/app/src/main/java');
const OUTBOUND = /\bSocket\(|\.openConnection\(|\bOkHttpClient\b|\bHttpURLConnection\b/;
const INBOUND = /\bServerSocket\(/;

function kotlinSources(dir: string): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap(name => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return kotlinSources(path);
    return name.endsWith('.kt') ? [path] : [];
  });
}

test('the Kotlin patterns catch each connection shape', () => {
  expect(OUTBOUND.test('val s = Socket(host, 80)')).toBe(true);
  expect(OUTBOUND.test('url.openConnection()')).toBe(true);
  expect(OUTBOUND.test('OkHttpClient.Builder()')).toBe(true);
  expect(OUTBOUND.test('ServerSocket(8787)')).toBe(false);
  expect(INBOUND.test('ServerSocket(8787)')).toBe(true);
});

test('only Fetcher connects out, and ServerSocket lives only in BridgeServer', () => {
  const offenders = kotlinSources(KOTLIN_ROOT).flatMap(path => {
    const text = readFileSync(path, 'utf8');
    const file = path.split('/').pop();
    const found: string[] = [];
    if (OUTBOUND.test(text) && file !== 'Fetcher.kt') found.push(`${path}: outbound`);
    if (INBOUND.test(text) && file !== 'BridgeServer.kt') found.push(`${path}: ServerSocket`);
    return found;
  });
  expect(offenders).toEqual([]);
  // The rule must see the real tree, not an empty directory.
  expect(kotlinSources(KOTLIN_ROOT).some(p => p.endsWith('Fetcher.kt'))).toBe(true);
});
```

In the existing test `no app source references fetch, XMLHttpRequest or WebSocket`, exempt the guard module, which names the APIs only to stub them. Replace

```ts
  const files = [
    ...sources(join(ROOT, 'src')),
```

with

```ts
  const files = [
    // guard.ts names the APIs only to replace them with throwing stubs (F16).
    ...sources(join(ROOT, 'src')).filter(
      f => f !== join(ROOT, 'src', 'net', 'guard.ts'),
    ),
```

Run: `npx jest __tests__/guard.test.ts __tests__/networkGuard.test.ts`
Expected:
- `guard.test.ts` FAILS: "Cannot find module '../src/net/guard'".
- The pattern test passes.
- The tree test passes, because Fetcher is the only outbound file. That is the rule holding, not a missed RED: the pattern test is what proves the rule can catch a violation.

- [ ] **Step 2: Implement the guard**

`src/net/guard.ts`:

```ts
// R-M09.2 as amended (F16): the source guard cannot see bundled third-party JS, so release
// builds replace the JS network APIs with stubs that throw, before any app module loads.
// This is the one file allowed to name them (see __tests__/networkGuard.test.ts).
export const GUARDED_NAMES = ['fetch', 'XMLHttpRequest', 'WebSocket'] as const;

const MESSAGE = 'network access is disabled in Read Me (R-M09)';

function blocked(): never {
  throw new Error(MESSAGE);
}

export function installRuntimeGuard(target: Record<string, unknown>): void {
  for (const name of GUARDED_NAMES) {
    Object.defineProperty(target, name, {
      value: blocked,
      writable: false,
      configurable: false,
      enumerable: false,
    });
  }
}
```

`src/net/installGuard.ts`:

```ts
// Imported first by index.js, so the stubs are in place before App and its imports load.
// Debug builds keep the APIs: Metro's reload and the dev tools use them.
import { installRuntimeGuard } from './guard';

if (!__DEV__) installRuntimeGuard(globalThis as unknown as Record<string, unknown>);
```

`index.js` becomes:

```js
/**
 * @format
 */

import './src/net/installGuard';
import { AppRegistry } from 'react-native';
import App from './App';
import { name as appName } from './app.json';

AppRegistry.registerComponent(appName, () => App);
```

Run: `npx jest __tests__/guard.test.ts __tests__/networkGuard.test.ts`
Expected: PASS.

- [ ] **Step 3: Check the product bundle still starts**

Run: `npm test && npm run typecheck && npm run lint && npm run -s build:release && npm run -s device:smoke`
Expected: all green and `device:smoke PASS`. This shows nothing in RN's release startup calls the stubbed APIs.

- [ ] **Step 4: Commit**

```bash
git add src/net index.js __tests__/guard.test.ts __tests__/networkGuard.test.ts
git commit -m "feat(build): release runtime stub over the JS network APIs; Kotlin outbound rule

Refs REA-16"
```

---

### Task 8: The on-device intake E2E, docs, and ship

**Files:**
- Create: `scripts/device-intake-e2e.sh`
- Modify: `package.json` (`device:intake` script)
- Modify: `srs.md` (R-M10 reason classes), `CONTEXT.md` (source layout), `AGENTS.md` (Known state, Quality gates)

- [ ] **Step 1: Write the E2E script**

`scripts/device-intake-e2e.sh`:

```bash
#!/usr/bin/env bash
# npm run device:intake - R-M02/R-M03/R-M04 on the phone, as a user would. Clears Read Me's
# data, then shares a link, a text and a dead link to ShareActivity with the app closed. It
# then opens the app and checks each item's state on screen, and that no log line carries the
# shared URL path or text (AGENTS.md 1). Uses the network: the link is weather.gov's
# lightning safety page (public domain, a Phase 1 fixture source).
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release

LINK='https://www.weather.gov/safety/lightning'
LINK_PATH_MARK='safety/lightning'
TEXT_MARK='Paragraph one of the shared note'
DEAD='http://127.0.0.1:9/nothing-here'

adb shell pm clear "$PKG" >/dev/null
adb logcat -c
share() {
  # The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$1")" \
    | adb shell >/dev/null
}
share "Lightning safety $LINK"
share "$TEXT_MARK, with a second sentence.

Paragraph two."
share "dead $DEAD"
echo "shared three items with the app closed; waiting for the requests"
sleep 25

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
fail=0
screen=""
for _ in $(seq 30); do
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  screen=$(adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true)
  if device_has "$screen" 'text="ready"' && device_has "$screen" 'fetch-failed: offline'; then
    break
  fi
  sleep 2
done
adb shell rm -f /sdcard/readme-ui.xml
readies=$(grep -o 'text="ready"' <<<"$screen" | wc -l)
[ "$readies" -ge 2 ] || { echo "FAIL: expected 2 ready items (link, text), saw $readies"; fail=1; }
device_has "$screen" 'fetch-failed: offline' || { echo "FAIL: dead link not fetch-failed: offline"; fail=1; }
device_has "$screen" 'text="fetching"' && { echo "FAIL: an item still fetching"; fail=1; }

logs=$(adb logcat -d)
if device_has "$logs" "$LINK_PATH_MARK|$TEXT_MARK|nothing-here"; then
  echo "FAIL: a log line carries a shared URL path or text"
  grep -nE "$LINK_PATH_MARK|$TEXT_MARK|nothing-here" <<<"$logs" | cut -c1-80 | sed 's/^/  /' | head -5
  fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
echo "device:intake $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
```

Add to `package.json` `scripts`: `"device:intake": "bash scripts/device-intake-e2e.sh"`.

- [ ] **Step 2: Run it on the phone**

Run: `npm run -s build:release && npm run -s device:intake`
Expected: `device:intake PASS`.

A FAIL is a real defect: reproduce it by hand on the phone first (AGENTS.md 16), then fix it test-first. If the log scan fails on `adbd ... service requested` lines, the share helper is putting text on argv; the stdin form above prevents that.

- [ ] **Step 3: Update the docs**

- **`srs.md` R-M10:** in the `fetch-failed` row, the reason classes become: "timeout, too large, too many redirects, HTTP status, offline, network, unsupported redirect, bad URL, interrupted".
- **`CONTEXT.md` source layout:** add these lines:
  - `android/.../intake/` Kotlin twin of share classification.
  - `store/` SQLite library and body files.
  - `fetch/` `Fetcher`, `FetchWorker`, `Recovery`.
  - `ShareActivity`, `ReadMeSpeechModule`.
  - `src/native/` codegen spec.
  - `src/library/` TS facade.
  - `src/net/` runtime network guard.
  - `scripts/device-intake-e2e.sh`.

  Remove "ReadMeSpeech planned".
- **`AGENTS.md` Known state:** replace "No native module, storage, playback or screens yet." with "Native library and intake (Phase 2, REA-16): Store, ShareActivity, Fetcher, recovery, ReadMeSpeech and a plain list; verified by `npm run device:intake` (<date>, build <hash>). No playback or real screens yet."
- **`AGENTS.md` Quality gates:** add `npm run device:intake   # shares a link, a text and a dead link on the phone; checks states on screen and logs (clears app data)`.

```bash
grep -rnP '\x{2014}' AGENTS.md CONTEXT.md srs.md scripts src android/app/src || echo "no em-dashes"
git add scripts/device-intake-e2e.sh package.json srs.md CONTEXT.md AGENTS.md
git commit -m "docs: record Phase 2 native library and intake; device:intake E2E (REA-16)

Refs REA-16"
```

- [ ] **Step 4: `/ship`**

Run `/ship`. The PR's Testing section quotes `device:intake PASS` and `device:smoke PASS` with the device, build and date. It also quotes the Kotlin test count and the F-Droid scan result after the dependency change.

Linear: REA-16 stays In Progress with the PR link as a comment, because there is no In Review status.
