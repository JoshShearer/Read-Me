// Compares the devcheck's device fingerprints with Node's, and checks median extract time
// (ADR 0006): a hard stall ceiling (5 s under 1 MB, 10 s above) fails the devcheck; F17's
// 1.5 s line for pages under 1 MB is a target, reported MET or MISSED.
// Fails closed: every one of the RUNS runs must report Hermes and a result for every fixture.
// Usage: devcheck-report.mjs expected.json device.txt RUNS
import { readFileSync } from 'node:fs';

const [expectedPath, devicePath, runsArg] = process.argv.slice(2);
const RUNS = Number(runsArg);
if (!Number.isInteger(RUNS) || RUNS < 1) {
  console.log(
    'devcheck: FAIL (RUNS argument missing or not a positive integer)',
  );
  process.exit(1);
}
const expected = JSON.parse(readFileSync(expectedPath, 'utf8'));
const runs = new Map();
const envs = new Map();
const throttled = [];
const thermalSeen = new Map();
for (const line of readFileSync(devicePath, 'utf8').split('\n')) {
  const t = /^run=(\d+) DEVCHECK_THERMAL (\{.*\})$/.exec(line);
  if (t) {
    // Heat throttles the CPU, so a launch that started above status 0 measures the phone's
    // temperature, not the code (fdaafaa: parse 2.4x slower at status 1).
    const th = JSON.parse(t[2]);
    thermalSeen.set(t[1], (thermalSeen.get(t[1]) ?? 0) + 1);
    if (th.status !== 0)
      throttled.push(`run ${t[1]} ${th.name} status ${th.status}`);
    continue;
  }
  const m = /^run=(\d+) DEVCHECK(_ENV)? (\{.*\})$/.exec(line);
  if (!m) continue;
  const d = JSON.parse(m[3]);
  if (m[2]) {
    // devcheck.sh launches once per fixture, so a run has one env line per launch.
    if (!envs.has(m[1])) envs.set(m[1], []);
    envs.get(m[1]).push(d);
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

const stageMedians = rs => {
  const out = {};
  for (const k of ['parse', 'depth', 'readability', 'contentParse', 'walk']) {
    const xs = rs.map(r => r.stages?.[k]).filter(x => typeof x === 'number');
    if (xs.length) out[k] = median(xs);
  }
  return out;
};

let ok = expected.length > 0;
for (let r = 1; r <= RUNS; r++) {
  const launches = envs.get(String(r)) ?? [];
  if (launches.length === 0 || !launches.every(e => e.hermes === true))
    ok = false;
}
const distinct = [
  ...new Set([...envs.values()].flat().map(e => JSON.stringify(e))),
];
console.log('env', distinct.join(' '), `(${envs.size} of ${RUNS} runs)`);
for (const e of expected) {
  const rs = [...(runs.get(e.name) ?? new Map()).values()];
  const parity = rs.length === RUNS && rs.every(r => r.hash === e.hash);
  const extract = rs.map(r => r.extractMs);
  const med = median(extract);
  const small = e.bytes !== undefined && e.bytes < 1024 * 1024;
  const ceiling = e.bytes === undefined ? null : small ? 5000 : 10000;
  const target = e.bytes === undefined ? null : small ? 1500 : 10000;
  const stall =
    ceiling === null || med === null ? 'n/a' : med <= ceiling ? 'PASS' : 'FAIL';
  const f17 =
    target === null || med === null ? 'n/a' : med <= target ? 'MET' : 'MISSED';
  if (!parity || stall === 'FAIL') ok = false;
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
      ceilingMs: ceiling,
      stall,
      f17TargetMs: target,
      f17,
      segmentMs: rs.map(r => r.segmentMs),
      stagesMedianMs: stageMedians(rs),
    }),
  );
}
// devcheck.sh writes one thermal line per launch; a launch without one cannot be shown cool.
for (let r = 1; r <= RUNS; r++) {
  const launches = (envs.get(String(r)) ?? []).length;
  if ((thermalSeen.get(String(r)) ?? 0) < launches) {
    throttled.push(
      `run ${r}: ${
        launches - (thermalSeen.get(String(r)) ?? 0)
      } launch(es) with no thermal status`,
    );
  }
}
if (throttled.length > 0) {
  ok = false;
  console.log(
    `throttled launches (timings not valid for F17): ${throttled.join('; ')}`,
  );
}
console.log(ok ? 'devcheck: PASS' : 'devcheck: FAIL');
process.exit(ok ? 0 : 1);
