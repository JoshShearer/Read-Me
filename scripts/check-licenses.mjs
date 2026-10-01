// R-M13 / AGENTS.md 14: every production npm dependency must be OSI-licensed. Reports every
// violation (not just the first) and exits 1 if there is any. The only exceptions are listed
// in DATA_EXCEPTIONS, each recorded in docs/adr/0002-cc-by-data-packages.md.
import {execFileSync} from 'node:child_process';

const OSI = new Set([
  'MIT', 'ISC', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', '0BSD', 'BlueOak-1.0.0',
  'Unlicense', 'Python-2.0', 'Zlib', 'MPL-2.0',
]);
// Data-only packages (no executable code reaches the app), owner decision 2026-10-01.
const DATA_EXCEPTIONS = {'caniuse-lite': 'CC-BY-4.0'};

function allowed(expr) {
  const e = expr.replace(/[()]/g, '').trim();
  if (e.includes(' OR ')) return e.split(' OR ').some(allowed);
  if (e.includes(' AND ')) return e.split(' AND ').every(allowed);
  return OSI.has(e);
}

const raw = execFileSync('npx', ['-y', 'license-checker-rseidelsohn@5.0.1', '--production',
  '--excludePrivatePackages', '--json'], {encoding: 'utf8', maxBuffer: 64 * 1024 * 1024});
const pkgs = JSON.parse(raw);
const bad = [];
let excepted = 0;
for (const [id, info] of Object.entries(pkgs)) {
  const name = id.slice(0, id.lastIndexOf('@'));
  const lic = [].concat(info.licenses ?? 'UNKNOWN').join(' OR ');
  if (allowed(lic)) continue;
  if (DATA_EXCEPTIONS[name] === lic) { excepted++; continue; }
  bad.push(`${id}: ${lic}`);
}
console.log(`${Object.keys(pkgs).length} production packages, ${excepted} recorded data exception(s)`);
for (const b of bad) console.log(`NOT ALLOWED ${b}`);
process.exit(bad.length ? 1 : 0);
