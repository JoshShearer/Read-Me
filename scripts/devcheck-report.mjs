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
