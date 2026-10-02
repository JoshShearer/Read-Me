// R-M13: third-party license notices for Settings > Licenses, written to
// android/app/src/main/assets/notices.json. npm entries carry the package's license text;
// Android (Maven) entries carry the license name and URL from the artifact's POM in the
// Gradle cache. --check regenerates in memory and exits 1 if the committed asset differs.
// The output has no timestamp, so the same tree gives the same bytes.
import {execFileSync} from 'node:child_process';
import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {homedir} from 'node:os';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const OUT = join(ROOT, 'android/app/src/main/assets/notices.json');
const GRADLE_CACHE = join(homedir(), '.gradle/caches/modules-2/files-2.1');
const MAX_TEXT = 20_000;

// Artifacts whose POM is absent from the cache or names no license. Each entry was checked by
// hand against the project's published license; add one only with that check.
const OVERRIDES = {
  // Its POM inherits the license from guava-parent 26.0-android (checked 2026-10-02).
  'com.google.guava:listenablefuture': {
    name: 'The Apache Software License, Version 2.0',
    url: 'http://www.apache.org/licenses/LICENSE-2.0.txt',
  },
};

export function parseCoordinates(tree) {
  const out = new Set();
  for (const line of tree.split('\n')) {
    const m = line.match(/--- ([\w.-]+):([\w.-]+):(\{strictly [^}]+\}|\S+)(?: -> (\S+))?/);
    if (!m) continue;
    const version = (m[4] ?? m[3]).replace(/^\{strictly (.+)\}$/, '$1');
    if (!/^\d/.test(version)) continue;
    out.add(`${m[1]}:${m[2]}:${version}`);
  }
  return [...out].sort();
}

export function parsePomLicenses(pom) {
  const out = [];
  for (const m of pom.matchAll(/<license>([\s\S]*?)<\/license>/g)) {
    const name = m[1].match(/<name>\s*([\s\S]*?)\s*<\/name>/)?.[1];
    const url = m[1].match(/<url>\s*([\s\S]*?)\s*<\/url>/)?.[1];
    if (name) out.push(url ? {name, url} : {name});
  }
  return out;
}

function pomFor(coord) {
  const [g, a, v] = coord.split(':');
  const dir = join(GRADLE_CACHE, g, a, v);
  if (!existsSync(dir)) return null;
  for (const hash of readdirSync(dir)) {
    const f = readdirSync(join(dir, hash)).find(n => n.endsWith('.pom'));
    if (f) return readFileSync(join(dir, hash, f), 'utf8');
  }
  return null;
}

function androidNotices() {
  const tree = execFileSync('./gradlew', ['-q', ':app:dependencies', '--configuration', 'releaseRuntimeClasspath'], {
    cwd: join(ROOT, 'android'),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  const missing = [];
  const out = [];
  for (const coord of parseCoordinates(tree)) {
    const [g, a, v] = coord.split(':');
    const name = `${g}:${a}`;
    const override = OVERRIDES[name];
    const lic = override ? [override] : parsePomLicenses(pomFor(coord) ?? '');
    if (lic.length === 0) {
      missing.push(coord);
      continue;
    }
    out.push({name, version: v, license: lic.map(l => l.name).join(' OR '), url: lic[0].url});
  }
  if (missing.length) {
    console.error(`no license found for ${missing.length} Android artifact(s); add a checked OVERRIDES entry:`);
    for (const m of missing) console.error(`  ${m}`);
    process.exit(1);
  }
  return out;
}

function npmNotices() {
  const raw = execFileSync('npx', ['-y', 'license-checker-rseidelsohn@5.0.1', '--production',
    '--excludePrivatePackages', '--json'], {cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024});
  return Object.entries(JSON.parse(raw)).map(([id, info]) => {
    const at = id.lastIndexOf('@');
    let text;
    if (info.licenseFile && existsSync(info.licenseFile) && !/readme/i.test(info.licenseFile)) {
      text = readFileSync(info.licenseFile, 'utf8').slice(0, MAX_TEXT);
    }
    return {
      name: id.slice(0, at),
      version: id.slice(at + 1),
      license: [].concat(info.licenses ?? 'UNKNOWN').join(' OR '),
      ...(text ? {text} : {}),
    };
  }).sort((x, y) => x.name.localeCompare(y.name) || x.version.localeCompare(y.version));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const json = JSON.stringify({npm: npmNotices(), android: androidNotices()}, null, 1) + '\n';
  if (process.argv.includes('--check')) {
    const now = existsSync(OUT) ? readFileSync(OUT, 'utf8') : '';
    if (now !== json) {
      console.error('notices.json is stale: run npm run notices and commit it');
      process.exit(1);
    }
    console.log('notices.json is current');
  } else {
    mkdirSync(dirname(OUT), {recursive: true});
    writeFileSync(OUT, json);
    const d = JSON.parse(json);
    console.log(`wrote ${d.npm.length} npm and ${d.android.length} Android notices`);
  }
}
