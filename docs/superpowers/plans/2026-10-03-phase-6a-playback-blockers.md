# Phase 6a: v1 playback blockers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the six REA-18 items the owner ruled v1 blockers (REA-25): playback that silently stalls or loses the user's place.

**Architecture:** `PlaybackQueue` (pure Kotlin, unit-tested with fakes) gains the rules: refuse a deleted item, survive a failed position save, pause instead of archiving on repeated engine errors, and declare the engine lost when `speak()` is refused or the engine stops speaking while the queue is playing. `PlaybackService` acts on "engine lost" by rebinding a fresh `TextToSpeech` once and resuming, and keeps a user-paused session in the foreground for 30 minutes so headset and notification controls keep working (ADR 0009). A new `npm run device:lifecycle` reproduces both device-visible bugs and proves the fixes.

**Tech Stack:** Kotlin (Android service, JUnit4 + Robolectric), bash device scripts over adb.

**Spec:** `srs.md` R-M07 (playback, controls with the screen off), R-M11 (position saved after every sentence and on pause; archive only at the last kept sentence). Issue REA-25, items from REA-18.

## Reproductions (done before this plan, reference device, build ef0fbc6, 2026-10-03)

ef0fbc6's app code is identical to main at 01eeefa/a293b71. Scratch scripts in the session scratchpad (`r25/*.sh`); Task 0 turns the two device-visible ones into `npm run device:lifecycle`.

1. **Paused service stopped by Android (REA-18 item 1): reproduced.** Play, pause in the Reader, Home. With no forcing, Android removed the service 60 s later (`dumpsys activity services`: 1 record at 45 s, 0 at 60 s); the notification and media session went with it; `KEYCODE_MEDIA_PLAY` then did nothing. `am make-uid-idle` gives the same result at once.
2. **Engine process dies mid-read (item 2): reproduced.** `am force-stop app.grapheneos.speechservices` 6 s into reading: 25 s later the media session still said `PLAYING(3)`, the `ReadMe:playback` wake lock was held, the Reader showed Pause, and no log line was written. Pause then Play: `playback resumed`, session PLAYING, wake lock re-acquired, engine still not running: silent.
3. **speak() returning ERROR ignored (item 6): reproduced** as part of 2 (the resume after the engine died queued nothing audible and nothing noticed).
4. **Delete racing load (item 3): confirmed by reading; reproduced by Task 1's test.** `ReadMeSpeechModule.deleteItem` calls `PlaybackHub.stopItem` then `store.delete`; `PlaybackService.load` checks `store.item(id)` then calls `queue.load`. A delete between the check and the load stops nothing (the queue does not hold the item yet) and the deleted item plays. Item ids are `AUTOINCREMENT` (`Store.kt:60`), never reused.
5. **A non-constraint SQLite exception in savePosition (item 4): confirmed by reading; reproduced by Task 2's test.** `Store.savePosition` catches only `SQLiteConstraintException`; `PlaybackQueue.advanceLocked` calls `sink.savePosition` before `topUpLocked`, so a throw (disk full, I/O) skips the top-up and the queue drains to silence while `playing` stays true.
6. **Every-utterance engine errors archive the item (item 5): reproduced by Task 3's test only.** Forcing per-utterance errors on the phone would mean clearing the TTS engine's voice data on the owner's phone; not done.

## Global Constraints

- AGENTS.md 1: logs carry ids, counts, offsets, durations, states and exception class names only. Never sentence text or a title.
- AGENTS.md 9: `setSpeechRate` is the only rate lever; a rebound speaker gets the queue's rate through `setRate`, nothing else scales speed.
- AGENTS.md 10: positions are (paragraph index, character offset); nothing persists a sentence index.
- AGENTS.md 11: the native service owns the queue; nothing here moves queue logic to JS.
- AGENTS.md 13: playback keeps its own `TextToSpeech`; a rebind replaces playback's instance only, never the bridge's (`TtsSynth`).
- AGENTS.md 14: no new dependency.
- R-M11: "The position ... MUST be saved by the service after every completed sentence and on pause/stop."
- R-M07: "Playback MUST continue with the screen off and the app in the background, through a foreground service with a media session, so lock-screen, notification and headset controls work (play/pause, next, previous)."
- Device runs take `.claude/device.lock/` via `scripts/lib/device.sh`; the phone has a secure lock screen (exit 5 means unlock it).

## Review Focus

1. A cold engine taking 8 s to first audio (AGENTS.md Known state, build 446b630) must not be mistaken for a dead engine: the stall check needs 3 ticks of 5 s (15 s) of `isSpeaking() == false` while playing. Pinned by Task 4's `aSlowFirstSentenceIsNotAStall` and by Task 7's check that `device:playback` and `device:gap` logs have no `engine lost` line.
2. A transient focus loss (a call) during the 30-minute paused window, then the call ends: the existing focus-resume path must still work, and the window must not keep a focus-paused session from its own hold. Pinned by Task 6's policy test `aFocusPauseIsHeldWhateverTheWindow`.
3. The engine dying again right after a rebind must not loop: at most one automatic rebind per user Play. Pinned by Task 5's Robolectric-free policy test `RecoveryPolicy` cases.
4. An item whose last one or two sentences fail must not be archived: errors that run into the end pause at the first failed sentence. Pinned by Task 3's `errorsAtTheEndPauseInsteadOfArchiving`.
5. Deleting an item that is not the one loaded (or not loaded yet) must not affect the loaded item. Pinned by Task 1's `stoppingAnotherItemLeavesPlaybackAlone`.

---

## File Structure

- Modify `android/app/src/main/java/io/loopstring/readme/playback/SentenceRow.kt`: `Speaker` gains `speak(): Boolean` and `isSpeaking()`; `PlaybackSink` gains `engineLost()`.
- Modify `android/app/src/main/java/io/loopstring/readme/playback/PlaybackQueue.kt`: deleted-item set, save guard, consecutive-error pause, lost-engine path, `checkStall()`, `swapSpeaker()`; `QueueStats` gains `saveErrors`.
- Modify `android/app/src/main/java/io/loopstring/readme/playback/TtsSpeaker.kt`: implement the new `Speaker` members.
- Modify `android/app/src/main/java/io/loopstring/readme/playback/Policies.kt`: `RecoveryPolicy`, `PauseWindow`; `ServiceLife.foreground` gains `pauseHeld`.
- Modify `android/app/src/main/java/io/loopstring/readme/playback/PlaybackService.kt`: stall tick, rebind on engine lost, paused window.
- Tests: `PlaybackQueueTest.kt`, `ServicePolicyTest.kt` (modify); `RecoveryPolicyTest.kt`, `PauseWindowTest.kt` (create).
- Create `scripts/device-lifecycle.sh` (Task 0); modify `package.json`, `scripts/device-playback-e2e.sh`, `scripts/device-gap.sh` (Task 7).
- Create `docs/adr/0009-paused-session-lifetime.md`; modify `srs.md` (R-M07), `AGENTS.md` (gate list, Known state).

---

### Task 0: `npm run device:lifecycle` fails on the current build

Turns the two device reproductions (1 and 2 above) into a repeatable script before any fix, and
watches it fail on the branch base.

**Files:**
- Create: `scripts/device-lifecycle.sh`
- Modify: `package.json` (add the script)

**Interfaces:**
- Consumes: existing log lines `playback start item=`, `playback paused item=`, `playback resumed item=`, `playback gaps n=`.
- Produces: the log lines it expects from later tasks: `playback engine lost; rebinding`, `playback engine rebound` (Task 5).

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# npm run device:lifecycle - REA-25 on the phone. (1) Pause, Home, wait 75 s (Android removed
# a paused background service at 60 s on the reference device), then a headset Play must
# resume (ADR 0009). (2) Force-stop the TTS engine while reading: playback must rebind it,
# resume, and keep reading. Clears app data. About 3 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release

ENGINE=$(adb shell settings get secure tts_default_synth | tr -d '\r')
text="Quietly the heron waited by the water while the morning went on without it."
for i in $(seq 2 30); do
  text+="

Paragraph $i begins here. It has a second sentence for the queue. A third one keeps the reader busy. The fourth closes paragraph $i."
done

ui() {
  adb shell rm -f /sdcard/readme-ui.xml
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
centre_of() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has $1 $2" >&2; exit 1; }
  set -- $b
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
logs() { adb logcat -d -s ReadMe:I; }
wait_log() {
  for _ in $(seq "$2"); do logs | grep -qE "$1" && return 0; sleep 1; done
  echo "FAIL: no '$1' within $2 s"; return 1
}
session_state() {
  adb shell dumpsys media_session | grep -A14 "package=$PKG" | grep -oE 'state=[A-Z]+' | head -1
}

fail=0
device_clear_app
adb logcat -c
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$text")" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
adb shell input tap $(centre_of text ready)
adb shell input tap $(centre_of content-desc 'trim done')
adb shell input tap $(centre_of content-desc play)
wait_log 'playback start item=1 ' 30 || fail=1
sleep 4

echo "== a paused session survives 75 s in the background (ADR 0009)"
adb shell input tap $(centre_of content-desc pause)
wait_log 'playback paused item=1 ' 10 || fail=1
adb shell input keyevent KEYCODE_HOME
sleep 75
n=$(adb shell dumpsys activity services "$PKG" | grep -cE 'ServiceRecord.*PlaybackService' || true)
if [ "$n" -ge 1 ]; then echo "ok: the service is still there"; else echo "FAIL: the service is gone"; fail=1; fi
adb logcat -c
adb shell input keyevent KEYCODE_MEDIA_PLAY
wait_log 'playback resumed item=1' 10 && echo "ok: headset Play resumed" || fail=1
[ "$(session_state)" = "state=PLAYING" ] && echo "ok: session PLAYING" || { echo "FAIL: session $(session_state)"; fail=1; }

echo "== the TTS engine dies mid-read (REA-18)"
sleep 4
adb logcat -c
adb shell am force-stop "$ENGINE"
wait_log 'playback engine lost; rebinding' 25 && echo "ok: loss noticed" || fail=1
wait_log 'playback engine rebound' 20 && echo "ok: engine rebound" || fail=1
sleep 8
adb logcat -c
adb shell input keyevent KEYCODE_MEDIA_PAUSE
wait_log 'playback gaps n=[1-9]' 10 && echo "ok: sentences spoken after the rebind" || fail=1

echo "== privacy and crashes"
if logs | grep -q 'Quietly the heron\|Paragraph [0-9]* begins'; then echo "FAIL: text in the log"; fail=1; fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then echo "FAIL: crash logged"; fail=1; fi

if [ "$fail" = 0 ]; then echo "device:lifecycle PASS"; else echo "device:lifecycle FAIL"; exit 1; fi
```

`package.json` scripts: `"device:lifecycle": "bash scripts/device-lifecycle.sh"`.

- [ ] **Step 2: Run it against the branch base (no fixes yet)**

```bash
npm run -s build:release
npm run -s device:lifecycle
```

Expected: `FAIL: the service is gone`, `FAIL: no 'playback resumed item=1' within 10 s`, `FAIL: no 'playback engine lost; rebinding' within 25 s`, ending `device:lifecycle FAIL`. Any other failure (a tap that finds nothing) is a script bug: fix the script and rerun until only these fail.

- [ ] **Step 3: Commit**

```bash
git add scripts/device-lifecycle.sh package.json
git commit -m "test(playback): device:lifecycle reproduces REA-18's paused-session and dead-engine bugs"
```

### Task 1: The queue refuses an item that is being deleted (REA-18 item 3)

**Files:**
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/PlaybackQueue.kt:31-42,100-105`
- Test: `android/app/src/test/java/io/loopstring/readme/playback/PlaybackQueueTest.kt`

**Interfaces:**
- Consumes: existing `PlaybackQueue.load(itemId, sentences, startIndex, rate): Boolean`, `stopItem(id: Long)`.
- Produces: `stopItem(id)` now also refuses every later `load(id, ...)` (returns false). No signature change.

- [ ] **Step 1: Write the failing tests** (append inside `class PlaybackQueueTest`)

```kotlin
  @Test fun anItemStoppedForDeletionCannotBeLoadedAfterwards() {
    // REA-18: deleteItem stops the queue, then deletes; the service may have checked the
    // item exists just before and load it just after.
    queue.stopItem(7)
    assertFalse(queue.load(7, rows, 0, 2.0f))
    assertTrue(speaker.spoken.isEmpty())
    assertNull(queue.snapshot().itemId)
  }

  @Test fun stoppingAnotherItemLeavesPlaybackAlone() {
    queue.load(7, rows, 0, 2.0f)
    queue.stopItem(8)
    assertTrue(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
    assertTrue(queue.load(9, rows, 0, 2.0f))
  }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.PlaybackQueueTest'`
Expected: FAIL in `anItemStoppedForDeletionCannotBeLoadedAfterwards` (load returns true). `stoppingAnotherItemLeavesPlaybackAlone` passes already; it guards the fix.

- [ ] **Step 3: Implement**

In `PlaybackQueue`, add the field after `private var errors = 0`:

```kotlin
  // Items stopped for deletion. Ids are AUTOINCREMENT (Store.kt), never reused.
  private val deleted = HashSet<Long>()
```

Change the first line of `load`'s body:

```kotlin
      if (itemId in deleted || startIndex !in sentences.indices) return false
```

Replace `stopItem`:

```kotlin
  /** The item is being deleted: stop without saving into it, and never load it again. */
  fun stopItem(id: Long) = synchronized(lock) {
    deleted += id
    if (itemId != id) return
    flushLocked()
    clearLocked()
  }
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.PlaybackQueueTest'`
Expected: PASS (all 17).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/playback/PlaybackQueue.kt android/app/src/test/java/io/loopstring/readme/playback/PlaybackQueueTest.kt
git commit -m "fix(playback): never load an item that is being deleted"
```

### Task 2: A failed position save does not stall the queue (REA-18 item 4)

**Files:**
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/PlaybackQueue.kt` (`QueueStats`, `advanceLocked`, `saveLocked`, `takeStats`)
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/PlaybackService.kt:362-367` (`logStats`)
- Test: `PlaybackQueueTest.kt`

**Interfaces:**
- Produces: `data class QueueStats(val gaps: GapSummary, val errors: Int, val saveErrors: Int)`.

- [ ] **Step 1: Write the failing test.** Give `FakeSink` a switch, then add the test:

```kotlin
  // in FakeSink:
    var failSaves = false
    override fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int) {
      // Stands in for SQLiteDiskIOException, a RuntimeException that Store.savePosition rethrows.
      if (failSaves) throw RuntimeException("disk I/O")
      saves += Triple(itemId, paragraphIndex, charOffset)
    }
```

```kotlin
  @Test fun aFailedSaveStillQueuesTheNextSentenceAndIsCounted() {
    // REA-18: Store.savePosition rethrows anything but a constraint error; a throw before the
    // top-up left the queue playing with nothing queued.
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    sink.failSaves = true
    queue.onDone("$g:0")
    assertEquals("$g:3", lastId())
    assertTrue(queue.snapshot().playing)
    assertTrue(queue.pause())
    assertFalse(queue.snapshot().playing)
    assertEquals(2, queue.takeStats().saveErrors)
  }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.PlaybackQueueTest'`
Expected: FAIL: the exception escapes `onDone` (or does not compile: `saveErrors` is not defined).

- [ ] **Step 3: Implement**

```kotlin
data class QueueStats(val gaps: GapSummary, val errors: Int, val saveErrors: Int)
```

Add `private var saveErrors = 0` beside `errors`. Add the guarded save and route both save sites through it:

```kotlin
  /** R-M11 asks for a save per sentence; a failed save must not stop the reading. */
  private fun saveRowLocked(id: Long, r: SentenceRow) {
    try {
      sink.savePosition(id, r.paragraphIndex, r.start)
    } catch (e: RuntimeException) {
      saveErrors++
    }
  }
```

In `advanceLocked` replace `sink.savePosition(id, rows[next].paragraphIndex, rows[next].start)` with `saveRowLocked(id, rows[next])`. In `saveLocked` replace the last line with `saveRowLocked(id, r)`. In `takeStats`:

```kotlin
  fun takeStats(): QueueStats = synchronized(lock) {
    val s = QueueStats(GapStats.summarize(gaps), errors, saveErrors)
    gaps.clear()
    errors = 0
    saveErrors = 0
    s
  }
```

In `PlaybackService.logStats`:

```kotlin
    if (g.count == 0 && st.errors == 0 && st.saveErrors == 0) return
    Log.i(TAG, "playback gaps n=${g.count} p50=${g.p50} p95=${g.p95} max=${g.max} stalls=${g.stalls} errors=${st.errors} saveErrors=${st.saveErrors}")
```

Check `scripts/` for parsers of that log line before committing: `grep -rn 'errors=' scripts/`. A regex ending in `errors=[0-9]+$` must be relaxed to accept the new field.

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A android/app/src scripts
git commit -m "fix(playback): a failed position save no longer stops the reading"
```

### Task 3: Repeated engine errors pause instead of archiving (REA-18 item 5)

**Files:**
- Modify: `PlaybackQueue.kt` (`onStart`, `onDone`, `onError`, `advanceLocked`, companion)
- Test: `PlaybackQueueTest.kt`

**Interfaces:**
- Produces: `PlaybackQueue.MAX_CONSECUTIVE_ERRORS = 3`.

- [ ] **Step 1: Write the failing tests**

```kotlin
  @Test fun threeErrorsInARowPauseAtTheFirstFailedSentence() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onError("$g:0")
    queue.onError("$g:1")
    queue.onError("$g:2")
    assertFalse(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
    assertEquals(Triple(7L, 0, 0), sink.saves.last()) // sentence 0 starts paragraph 0 at 0
    assertTrue(sink.finished.isEmpty())
  }

  @Test fun anErrorBetweenGoodSentencesStillAdvances() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onError("$g:0")
    queue.onStart("$g:1")
    queue.onDone("$g:1")
    queue.onError("$g:2")
    assertTrue(queue.snapshot().playing)
  }

  @Test fun errorsAtTheEndPauseInsteadOfArchiving() {
    queue.load(7, rows, 4, 2.0f)
    val g = gen(lastId())
    queue.onDone("$g:4")
    queue.onError("$g:5")
    assertTrue(sink.finished.isEmpty())
    assertFalse(queue.snapshot().playing)
    assertEquals(Triple(7L, 3, 5), sink.saves.last()) // sentence 5 is paragraph 3 offset 5
  }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.PlaybackQueueTest'`
Expected: FAIL in the first and third (the queue keeps advancing; the third archives item 7).

- [ ] **Step 3: Implement**

Fields beside `errors`:

```kotlin
  private var consecutiveErrors = 0
  private var firstErrorIndex = -1
```

`onStart` and `onDone` reset the run: add `consecutiveErrors = 0` as the first line inside each after the `indexOf` check. Replace `onError`:

```kotlin
  fun onError(id: String) = synchronized(lock) {
    val i = indexOf(id) ?: return
    errors++
    lastDoneAt = -1
    if (consecutiveErrors == 0) firstErrorIndex = i
    consecutiveErrors++
    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
      pauseAtLocked(firstErrorIndex)
      return
    }
    advanceLocked(i)
  }

  /** Stops at [index] and saves it, so the user resumes where the engine started failing. */
  private fun pauseAtLocked(index: Int) {
    flushLocked()
    playing = false
    current = index
    consecutiveErrors = 0
    saveLocked()
    publishLocked()
  }
```

In `advanceLocked`, at the top of the `if (next >= rows.size)` branch, before `flushLocked()`:

```kotlin
      if (consecutiveErrors > 0) {
        // The last sentences failed: that is not reading to the end (R-M11 archives only then).
        pauseAtLocked(firstErrorIndex)
        return
      }
```

Companion: `const val MAX_CONSECUTIVE_ERRORS = 3`.

- [ ] **Step 4: Run to verify they pass**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.PlaybackQueueTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "fix(playback): engine errors pause at the first failed sentence instead of archiving"
```

### Task 4: The queue notices a dead engine (REA-18 items 2 and 6, queue side)

**Files:**
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/SentenceRow.kt:18-32`
- Modify: `PlaybackQueue.kt` (`speaker` becomes `var`, `topUpLocked`, new `checkStall`, `swapSpeaker`, `lostLocked`)
- Modify: `TtsSpeaker.kt:59-61` (speak), add `isSpeaking`
- Modify: `PlaybackService.kt` (add an `engineLost()` override that Task 5 fills in)
- Test: `PlaybackQueueTest.kt`

**Interfaces:**
- Produces:
  - `interface Speaker { fun speak(id: String, text: String): Boolean; fun stop(); fun setRate(rate: Float); fun isSpeaking(): Boolean }`
  - `interface PlaybackSink { ...; fun engineLost() }` (called with the queue lock held)
  - `PlaybackQueue.checkStall(): Boolean`, `PlaybackQueue.swapSpeaker(s: Speaker)`, `PlaybackQueue.STALL_TICKS = 3`

- [ ] **Step 1: Write the failing tests.** Update the fakes first:

```kotlin
  private class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>()
    var stops = 0
    val rates = mutableListOf<Float>()
    var refuse = false
    var speaking = true
    override fun speak(id: String, text: String): Boolean { if (refuse) return false; spoken += id; return true }
    override fun stop() { stops++ }
    override fun setRate(rate: Float) { rates += rate }
    override fun isSpeaking() = speaking
  }
  // in FakeSink:
    var lost = 0
    override fun engineLost() { lost++ }
```

```kotlin
  @Test fun aRefusedSpeakPausesAndReportsTheEngineLost() {
    // REA-18: after the engine process died, resume "played" silently with the wake lock held.
    speaker.refuse = true
    queue.load(7, rows, 2, 2.0f)
    assertFalse(queue.snapshot().playing)
    assertEquals(1, sink.lost)
    assertEquals(Triple(7L, 1, 0), sink.saves.last())
  }

  @Test fun anEngineThatStopsSpeakingIsLostAfterThreeChecks() {
    queue.load(7, rows, 0, 2.0f)
    speaker.speaking = false
    assertFalse(queue.checkStall())
    assertFalse(queue.checkStall())
    assertTrue(queue.checkStall())
    assertFalse(queue.snapshot().playing)
    assertEquals(1, sink.lost)
  }

  @Test fun aSlowFirstSentenceIsNotAStall() {
    // A cold engine took 8 s to first audio (AGENTS.md); progress or speaking resets the count.
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    speaker.speaking = false
    queue.checkStall()
    queue.checkStall()
    queue.onStart("$g:0")
    assertFalse(queue.checkStall())
    assertFalse(queue.checkStall())
    assertTrue(queue.snapshot().playing)
    assertEquals(0, sink.lost)
  }

  @Test fun aPausedQueueIsNeverStalled() {
    queue.load(7, rows, 0, 2.0f)
    queue.pause()
    speaker.speaking = false
    repeat(5) { assertFalse(queue.checkStall()) }
  }

  @Test fun aSwappedSpeakerGetsTheRateAndTheNextResume() {
    queue.load(7, rows, 2, 1.5f)
    speaker.refuse = true
    queue.next() // refused: lost
    val fresh = FakeSpeaker()
    queue.swapSpeaker(fresh)
    assertEquals(listOf(1.5f), fresh.rates)
    assertTrue(queue.resume())
    assertEquals(3, fresh.spoken.size)
  }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.PlaybackQueueTest'`
Expected: compile failure (`checkStall`, `swapSpeaker`, `engineLost`, `isSpeaking` do not exist).

- [ ] **Step 3: Implement**

`SentenceRow.kt`:

```kotlin
interface Speaker {
  /** False when the engine refused the utterance: it is not bound, or its process died. */
  fun speak(id: String, text: String): Boolean
  fun stop()
  fun setRate(rate: Float)
  /** Whether the engine is speaking or has utterances queued. */
  fun isSpeaking(): Boolean
}
```

and in `PlaybackSink` add:

```kotlin
  /** The engine refused work or stopped speaking while the queue played; the queue has paused. */
  fun engineLost()
```

`PlaybackQueue`: constructor `private var speaker: Speaker`; field `private var stallTicks = 0`; reset `stallTicks = 0` in `onStart`, `onDone`, `onError` and `restartLocked`. Replace `topUpLocked` and add:

```kotlin
  private fun topUpLocked() {
    while (playing && queuedUntil < rows.size && queuedUntil - current < AHEAD) {
      if (!speaker.speak("$generation:$queuedUntil", rows[queuedUntil].text)) {
        lostLocked()
        return
      }
      queuedUntil++
    }
  }

  /** Called every STALL_TICK_MS by the service while playing. True when the engine is lost. */
  fun checkStall(): Boolean = synchronized(lock) {
    if (!playing || speaker.isSpeaking()) {
      stallTicks = 0
      return false
    }
    if (++stallTicks < STALL_TICKS) return false
    lostLocked()
    true
  }

  /** A rebound engine (PlaybackService). Paused stays paused; the rate is applied once more. */
  fun swapSpeaker(s: Speaker) = synchronized(lock) {
    speaker = s
    s.setRate(rate)
  }

  private fun lostLocked() {
    flushLocked()
    playing = false
    stallTicks = 0
    saveLocked()
    publishLocked()
    sink.engineLost()
  }
```

`restartLocked` publishes after `topUpLocked`; when the top-up lost the engine, `playing` is already false, so that second publish repeats the paused snapshot. Leave it.

Companion: `const val STALL_TICKS = 3`.

`TtsSpeaker`:

```kotlin
  override fun speak(id: String, text: String): Boolean =
    tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS

  override fun isSpeaking(): Boolean = runCatching { tts.isSpeaking }.getOrDefault(false)
```

`PlaybackService` (temporary until Task 5): add `override fun engineLost() {}` under `// --- PlaybackSink`.

- [ ] **Step 4: Run to verify they pass**

Run: `cd android && ./gradlew -q testDebugUnitTest`
Expected: PASS, whole suite (the `Speaker` change compiles everywhere).

- [ ] **Step 5: Commit**

```bash
git add android/app/src
git commit -m "fix(playback): the queue pauses and reports a lost engine instead of playing silence"
```

### Task 5: The service rebinds a lost engine once and resumes (REA-18 items 2 and 6, service side)

**Files:**
- Modify: `Policies.kt` (add `RecoveryPolicy`)
- Modify: `PlaybackService.kt` (`speaker` becomes `var`, tick, `engineLost`, `onReady`, `handle`, `load`, `startPlaying`, `releasePlayingResources`)
- Test: create `android/app/src/test/java/io/loopstring/readme/playback/RecoveryPolicyTest.kt`

**Interfaces:**
- Consumes: Task 4's `queue.checkStall()`, `queue.swapSpeaker(s)`, `PlaybackSink.engineLost()`.
- Produces: `object RecoveryPolicy { const val MAX_REBINDS = 1; fun rebind(rebindsSinceUserPlay: Int, rebinding: Boolean): Boolean }`; log lines `playback engine lost; rebinding`, `playback engine rebound`, `playback engine lost; paused` (Task 7's script reads them).

- [ ] **Step 1: Write the failing test**

```kotlin
package io.loopstring.readme.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** REA-18: a dead TTS engine is rebound once per user Play, never in a loop. */
class RecoveryPolicyTest {
  @Test fun theFirstLossRebinds() = assertTrue(RecoveryPolicy.rebind(rebindsSinceUserPlay = 0, rebinding = false))

  @Test fun aSecondLossBeforeTheUserPlaysAgainStaysPaused() =
    assertFalse(RecoveryPolicy.rebind(rebindsSinceUserPlay = 1, rebinding = false))

  @Test fun aLossWhileRebindingIsIgnored() = assertFalse(RecoveryPolicy.rebind(rebindsSinceUserPlay = 0, rebinding = true))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.RecoveryPolicyTest'`
Expected: compile failure, `RecoveryPolicy` unresolved.

- [ ] **Step 3: Implement the policy** (append to `Policies.kt`)

```kotlin
/**
 * REA-18: when playback's engine dies (its process killed or crashed), TextToSpeech does not
 * rebind by itself. The service rebinds once and resumes; a second loss before the user presses
 * Play again leaves playback paused at the saved position.
 */
object RecoveryPolicy {
  const val MAX_REBINDS = 1
  fun rebind(rebindsSinceUserPlay: Int, rebinding: Boolean) = !rebinding && rebindsSinceUserPlay < MAX_REBINDS
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.RecoveryPolicyTest'`
Expected: PASS.

- [ ] **Step 5: Wire it into the service**

Fields (replace `private lateinit var speaker: TtsSpeaker` with a `var`, add the rest beside `waiting`):

```kotlin
  private lateinit var speaker: TtsSpeaker
  private var rebinding = false
  private var rebinds = 0
  private val stallTick = object : Runnable {
    override fun run() {
      if (destroyed) return
      queue.checkStall()
      main.postDelayed(this, STALL_TICK_MS)
    }
  }
```

(`lateinit var` is already a `var`; keep it, it is reassigned below.)

`startPlaying()`: after `claim.claim()` add

```kotlin
    main.removeCallbacks(stallTick)
    main.postDelayed(stallTick, STALL_TICK_MS)
```

`releasePlayingResources(...)`: first line `main.removeCallbacks(stallTick)`.

A user's Play resets the count. In `handle`:

```kotlin
    PlaybackCommands.ACTION_PLAY -> { rebinds = 0; queue.resume() }
    PlaybackCommands.ACTION_TOGGLE ->
      if (queue.snapshot().playing) handle(PlaybackCommands.ACTION_PAUSE) else { rebinds = 0; queue.resume() }
```

and in `load(r)` before `queue.load(...)`: `rebinds = 0`.

Replace the Task 4 placeholder:

```kotlin
  override fun engineLost() {
    main.post { if (!destroyed) recoverEngine() }
  }
```

Add under the main-thread section:

```kotlin
  /** REA-18: rebind playback's own engine (never the bridge's) and resume where it stopped. */
  private fun recoverEngine() {
    if (!RecoveryPolicy.rebind(rebinds, rebinding)) {
      if (!rebinding) Log.i(TAG, "playback engine lost; paused")
      return
    }
    rebinds++
    rebinding = true
    Log.i(TAG, "playback engine lost; rebinding")
    speaker.shutdown()
    speaker = TtsSpeaker(this, this, Settings(this).voice)
    queue.swapSpeaker(speaker)
  }
```

At the top of `onReady`:

```kotlin
    if (rebinding) {
      rebinding = false
      PlaybackHub.engine = status.wire
      if (status == TtsSpeaker.EngineStatus.READY) {
        Log.i(TAG, "playback engine rebound")
        queue.resume()
      } else {
        Log.i(TAG, "playback engine ${status.wire}")
      }
      return
    }
```

Companion: `private const val STALL_TICK_MS = 5_000L`.

Note `shutdown()` on a dead `TextToSpeech` must not throw; `TtsSpeaker.shutdown` calls `tts.stop()` and `tts.shutdown()`, both of which return error codes on a lost connection. If the device run (Task 7) shows a throw, wrap them in `runCatching`.

- [ ] **Step 6: Run the suite**

Run: `cd android && ./gradlew -q testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/app/src
git commit -m "fix(playback): rebind a dead TTS engine once and resume at the saved sentence"
```

### Task 6: A paused session stays controllable for 30 minutes (REA-18 item 1, ADR 0009)

**Files:**
- Modify: `Policies.kt` (`ServiceLife.foreground`, add `PauseWindow`)
- Modify: `PlaybackService.kt` (`onSnapshot`, `syncForeground`, `startPlaying`, `end`)
- Create: `docs/adr/0009-paused-session-lifetime.md`; modify `srs.md` R-M07
- Test: create `PauseWindowTest.kt`; modify `ServicePolicyTest.kt`

**Interfaces:**
- Produces: `object PauseWindow { const val HOLD_MS = 30 * 60 * 1000L; fun held(pausedAt: Long, now: Long): Boolean }`; `ServiceLife.foreground(playing, pausedForFocus, bridgeOn, pauseHeld: Boolean = false)`.

- [ ] **Step 1: Write the failing tests**

`PauseWindowTest.kt`:

```kotlin
package io.loopstring.readme.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REA-18, ADR 0009: Android stopped a paused, backgrounded service after 60 s (reference
 * device), taking the headset and notification controls with it. A user pause now keeps the
 * foreground for 30 minutes.
 */
class PauseWindowTest {
  private val t = 1_000_000L

  @Test fun heldRightAfterAPause() = assertTrue(PauseWindow.held(pausedAt = t, now = t))
  @Test fun heldJustInsideThirtyMinutes() = assertTrue(PauseWindow.held(t, t + PauseWindow.HOLD_MS - 1))
  @Test fun releasedAtThirtyMinutes() = assertFalse(PauseWindow.held(t, t + PauseWindow.HOLD_MS))
  @Test fun notHeldWhenNotPaused() = assertFalse(PauseWindow.held(pausedAt = -1, now = t))
}
```

In `ServicePolicyTest.theBridgeKeepsTheServiceInTheForeground` add:

```kotlin
    assertTrue(ServiceLife.foreground(playing = false, pausedForFocus = false, bridgeOn = false, pauseHeld = true))
```

and a new test:

```kotlin
  @Test fun aFocusPauseIsHeldWhateverTheWindow() {
    assertTrue(ServiceLife.foreground(playing = false, pausedForFocus = true, bridgeOn = false, pauseHeld = false))
  }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.*'`
Expected: compile failure (`PauseWindow`, `pauseHeld`).

- [ ] **Step 3: Implement the policy**

In `Policies.kt`:

```kotlin
/** R-M12, ADR 0005; ADR 0009: a user pause holds the foreground for PauseWindow.HOLD_MS. */
object ServiceLife {
  fun foreground(playing: Boolean, pausedForFocus: Boolean, bridgeOn: Boolean, pauseHeld: Boolean = false) =
    playing || PausePolicy.hold(pausedForFocus).foreground || pauseHeld || bridgeOn

  fun keepAlive(hasItem: Boolean, bridgeOn: Boolean) = hasItem || bridgeOn
}

/**
 * ADR 0009: how long a user-paused session stays in the foreground, so lock-screen,
 * notification and headset Play keep working. [pausedAt] is elapsedRealtime, or -1.
 */
object PauseWindow {
  const val HOLD_MS = 30 * 60 * 1000L
  fun held(pausedAt: Long, now: Long) = pausedAt >= 0 && now - pausedAt < HOLD_MS
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd android && ./gradlew -q testDebugUnitTest --tests 'io.loopstring.readme.playback.*'`
Expected: PASS.

- [ ] **Step 5: Wire it into the service**

Field: `private var pausedAt = -1L` and

```kotlin
  private val pauseExpired = Runnable { if (!destroyed) syncForeground(queue.snapshot()) }
```

In `onSnapshot`'s paused branch, after `releasePlayingResources(...)`:

```kotlin
      if (!pausedForFocus) {
        pausedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(pauseExpired)
        main.postDelayed(pauseExpired, PauseWindow.HOLD_MS)
      }
```

In `startPlaying()` first lines: `pausedAt = -1L` and `main.removeCallbacks(pauseExpired)`. In `end()` first line: `main.removeCallbacks(pauseExpired)` then `pausedAt = -1L`.

In `syncForeground` replace the condition and comment:

```kotlin
    val held = PauseWindow.held(pausedAt, SystemClock.elapsedRealtime())
    if (ServiceLife.foreground(s.playing, pausedForFocus, bridgeOn, held)) {
      goForeground(s)
    } else {
      // Paused for longer than ADR 0009's window: the notification stays (with Play) but can be
      // swiped away, and the system may stop the service; the position is already saved.
```

- [ ] **Step 6: Write ADR 0009 and amend R-M07**

`docs/adr/0009-paused-session-lifetime.md`:

```markdown
# ADR 0009: A paused session stays in the foreground for 30 minutes

Date: 2026-10-03. Status: accepted. Issue: REA-25 (from REA-18).

## Context

R-M07 requires lock-screen, notification and headset controls to work with the app in the
background. Phase 3 released the foreground on a user pause. On the reference device
(Pixel 9 Pro XL, Android 17, build ef0fbc6), Android then stopped the paused service 60 s
after Read Me went to the background; the notification and the media session went with it,
and a headset Play did nothing. The service cannot rebuild the queue by itself after that:
the sentence list comes from JS (R-M07, AGENTS.md 11).

## Decision

A user pause keeps the `mediaPlayback` foreground service, its notification and its media
session for 30 minutes (`PauseWindow.HOLD_MS`). Nothing else is held: no wake lock, no audio
focus, no noisy receiver. After 30 minutes the service leaves the foreground as before; the
notification stays until swiped or until Android stops the service. A focus pause (a call) is
unchanged: it holds everything until focus returns.

## Consequences

- Headset, lock-screen and notification Play work for 30 minutes after a pause. After that,
  resuming needs the app; the position is saved (R-M11).
- The notification cannot be swiped away for those 30 minutes on Android versions that keep
  foreground notifications ongoing.
- 30 minutes is a choice, not a measurement: long enough for a conversation or a stop, short
  enough not to sit in the status bar all day. Change `PauseWindow.HOLD_MS` and this ADR together.
```

In `srs.md` R-M07, after the bullet "Playback MUST continue with the screen off ...", add:

```markdown
- After a user pause, those controls MUST keep working for at least 30 minutes; after that
  the session may end and resuming may need the app (ADR 0009, amended 2026-10-03, REA-25).
```

- [ ] **Step 7: Run the suite and commit**

Run: `cd android && ./gradlew -q testDebugUnitTest`
Expected: PASS.

```bash
git add android/app/src docs/adr/0009-paused-session-lifetime.md srs.md
git commit -m "fix(playback): keep a paused session controllable for 30 minutes (ADR 0009)"
```

### Task 7: Device runs and docs

**Files:**
- Modify: `scripts/device-playback-e2e.sh` and `scripts/device-gap.sh` (no `engine lost` line), `AGENTS.md` (gate list, Known state)

**Interfaces:**
- Consumes: Task 0's `scripts/device-lifecycle.sh`; Task 5's log lines.

- [ ] **Step 1: Guard the existing runs against false stalls**

In `scripts/device-playback-e2e.sh` and `scripts/device-gap.sh`, beside each script's existing privacy/crash checks, add:

```bash
if logs | grep -q 'engine lost'; then echo "FAIL: a stall was declared during normal playback"; fail=1; fi
```

(`device-gap.sh` may name its log function differently; use its own reader of `ReadMe:I` lines.)

- [ ] **Step 2: Build and run everything on the phone**

```bash
npm run -s build:release
npm run -s device:lifecycle
npm run -s device:playback
npm run -s device:ui
npm run -s device:bridge
GAP_MINUTES=10 npm run -s device:gap
```

Expected: each prints PASS. `device:gap` reports `stalls=0 errors=0` and its p95/max within R-M07 (300 ms / 1000 ms); compare against the Phase 3 numbers in AGENTS.md (n=92 p50=7 p95=17 max=31). `device:lifecycle` FAIL here means a fix is wrong: debug, do not loosen the script.

- [ ] **Step 3: Docs**

`AGENTS.md` quality-gates block, after `device:playback`:

```
npm run device:lifecycle  # REA-25: a paused session survives 75 s in the background and headset Play resumes it (ADR 0009); a force-stopped TTS engine is rebound and reading resumes (clears app data; about 3 min)
```

`AGENTS.md` Known state, Playback entry: append one sentence with the build and date of the Step 4 runs and the gap numbers measured, and note "a paused session holds the foreground 30 min (ADR 0009); a dead engine is rebound once per user Play".

- [ ] **Step 4: Commit**

```bash
git add scripts AGENTS.md
git commit -m "docs(playback): record device:lifecycle and the Phase 6a device runs"
```

---

## Not in this plan (stay in REA-18)

Notification action during a cold engine start; the 4 h wake lock not renewed; `setRate` with no item; `onReady` ignoring `destroyed`; the notification rebuilt every sentence; device-script and build-guard hardening; R-M06 timing. At ship time, edit REA-18 to remove the six items fixed here and link REA-25.
