// R-M13 / AGENTS.md 14: every production npm dependency must be OSI-licensed. Reports every
// violation (not just the first) and exits 1 if there is any. The only exceptions are listed
// in DATA_EXCEPTIONS, each recorded in docs/adr/0002-cc-by-data-packages.md.
import {execFileSync} from 'node:child_process';
import {allowed} from './license-expr.mjs';

// Data-only packages (no executable code reaches the app), owner decision 2026-10-01.
const DATA_EXCEPTIONS = {'caniuse-lite': 'CC-BY-4.0'};

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
