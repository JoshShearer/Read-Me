// REA-50: Trim and Reader paragraphs carry no accessibilityLabel (TalkBack reads their text),
// so a device script that still looks for them by content-desc would find nothing on the phone.
// They are found by resource-id (their testID) instead.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readdirSync, readFileSync} from 'node:fs';
import {join} from 'node:path';

const DIR = 'scripts';
const scripts = readdirSync(DIR).filter(f => f.endsWith('.sh')).map(f => join(DIR, f));

const code = file =>
  readFileSync(file, 'utf8')
    .split('\n')
    .map((line, i) => ({line, n: i + 1}))
    .filter(({line}) => !/^\s*#/.test(line));

test('no device script finds a paragraph by content-desc', () => {
  assert.ok(scripts.length >= 5, `only ${scripts.length} scripts`);
  const bad = [];
  for (const file of scripts) {
    for (const {line, n} of code(file)) {
      if (/content-desc.*paragraph/.test(line)) bad.push(`${file}:${n}: ${line.trim()}`);
    }
  }
  assert.deepEqual(bad, []);
});

test("device-ui-e2e's centre_of names its attribute on every call", () => {
  const file = join(DIR, 'device-ui-e2e.sh');
  const bad = [];
  for (const {line, n} of code(file)) {
    for (const m of line.matchAll(/centre_of\s+([^)\s]+)/g)) {
      if (!['content-desc', 'resource-id', 'text'].includes(m[1])) bad.push(`${file}:${n}: ${line.trim()}`);
    }
  }
  assert.deepEqual(bad, []);
});

test('the resource-id selector tells a paragraph from the same paragraph cut', () => {
  const kept = '<node index="0" text="" resource-id="paragraph 2" class="android.view.ViewGroup" bounds="[0,10][100,20]"/>';
  const cut = '<node index="0" text="" resource-id="paragraph 2 cut" class="android.view.ViewGroup" bounds="[0,10][100,20]"/>';
  const ere = /resource-id="paragraph 2"[^>]*bounds=/;
  assert.match(kept, ere);
  assert.doesNotMatch(cut, ere);
  assert.match(cut, /resource-id="paragraph 2 cut"/);
});
