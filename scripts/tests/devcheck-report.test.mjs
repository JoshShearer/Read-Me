// The devcheck report is the evidence that the pipeline matches Node on Hermes. It must fail
// closed: a run that was not Hermes, or fewer runs than were asked for, is not that evidence.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {mkdtempSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';

const dir = mkdtempSync(join(tmpdir(), 'devcheck-report-'));
const expectedPath = join(dir, 'expected.json');
writeFileSync(expectedPath, JSON.stringify([{name: 'page', bytes: 1000, hash: 'h1', paragraphs: 1, sentences: 1}]));

function report(lines, runs) {
  const devicePath = join(dir, `device-${Math.random()}.txt`);
  writeFileSync(devicePath, lines.join('\n') + '\n');
  return spawnSync('node', ['scripts/devcheck-report.mjs', expectedPath, devicePath, String(runs)], {encoding: 'utf8'});
}
const env = (run, hermes) => `run=${run} DEVCHECK_ENV {"hermes":${hermes},"intlSegmenter":false}`;
const row = run => `run=${run} DEVCHECK {"name":"page","hash":"h1","extractMs":10,"segmentMs":1}`;

test('three Hermes runs that match pass', () => {
  const r = report([1, 2, 3].flatMap(n => [env(n, true), row(n)]), 3);
  assert.equal(r.status, 0, r.stdout);
  assert.match(r.stdout, /devcheck: PASS/);
});

test('a run that was not on Hermes fails', () => {
  const r = report([env(1, true), row(1), env(2, false), row(2), env(3, true), row(3)], 3);
  assert.equal(r.status, 1, r.stdout);
  assert.match(r.stdout, /devcheck: FAIL/);
});

test('fewer fixture results than runs fails', () => {
  const r = report([1, 2, 3].map(n => env(n, true)).concat(row(1)), 3);
  assert.equal(r.status, 1, r.stdout);
});

test('a missing env line for a run fails', () => {
  const r = report([env(1, true), row(1), row(2), env(3, true), row(3)], 3);
  assert.equal(r.status, 1, r.stdout);
});

test('an empty expectation file fails', () => {
  const empty = join(dir, 'empty.json');
  writeFileSync(empty, '[]');
  const devicePath = join(dir, 'device-empty.txt');
  writeFileSync(devicePath, [1, 2, 3].map(n => env(n, true)).join('\n') + '\n');
  const r = spawnSync('node', ['scripts/devcheck-report.mjs', empty, devicePath, '3'], {encoding: 'utf8'});
  assert.equal(r.status, 1, r.stdout);
});

test('a duplicated line does not stand in for a missing run', () => {
  const r = report([env(1, true), row(1), row(1), env(2, true), row(2), env(3, true)], 3);
  assert.equal(r.status, 1, r.stdout);
});

test('with one launch per fixture, any launch not on Hermes fails its run', () => {
  const r = report([env(1, false), env(1, true), row(1), env(2, true), row(2), env(3, true), row(3)], 3);
  assert.equal(r.status, 1, r.stdout);
});
