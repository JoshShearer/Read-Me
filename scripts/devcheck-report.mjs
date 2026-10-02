// Compares the devcheck's device fingerprints with Node's and applies SPIKE-02's F17 line
// (median extract time: 10 s for the 5 MB page, 1.5 s for every page under 1 MB).
// Fails closed: every one of the RUNS runs must report Hermes and a result for every fixture.
// Usage: devcheck-report.mjs expected.json device.txt RUNS
import { readFileSync } from 'node:fs';

const [expectedPath, devicePath, runsArg] = process.argv.slice(2);
const RUNS = Number(runsArg);
if (!Number.isInteger(RUNS) || RUNS < 1) {
  console.log('devcheck: FAIL (RUNS argument missing or not a positive integer)');
  process.exit(1);
}
const expected = JSON.parse(readFileSync(expectedPath, 'utf8'));
const runs = new Map();
const envs = new Map();
for (const line of readFileSync(devicePath, 'utf8').split('\n')) {
  const m = /^run=(\d+) DEVCHECK(_ENV)? (\{.*\})$/.exec(line);
  if (!m) continue;
  const d = JSON.parse(m[3]);
  if (m[2]) {
    envs.set(m[1], d);
    continue;
  }
  // One result per run id: a line logcat duplicated must not stand in for a missing run.
  if (!runs.has(d.name)) runs.set(d.name, new Map());
  runs.get(d.name).set(m[1], d);
}

const median = xs => {
  const s = [...xs].sort((a, b) => a - b);
  return s.length ? s[Math.floor(s.length / 2)] : null;
};

let ok = expected.length > 0;
for (let r = 1; r <= RUNS; r++) {
  if (envs.get(String(r))?.hermes !== true) ok = false;
}
const distinct = [...new Set([...envs.values()].map(e => JSON.stringify(e)))];
console.log('env', distinct.join(' '), `(${envs.size} of ${RUNS} runs)`);
for (const e of expected) {
  const rs = [...(runs.get(e.name) ?? new Map()).values()];
  const parity = rs.length === RUNS && rs.every(r => r.hash === e.hash);
  const extract = rs.map(r => r.extractMs);
  const med = median(extract);
  const limit =
    e.bytes === undefined ? null : e.bytes < 1024 * 1024 ? 1500 : 10000;
  const f17 =
    limit === null || med === null ? 'n/a' : med <= limit ? 'PASS' : 'FAIL';
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
