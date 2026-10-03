// R-M13: third-party license notices for Settings > Licenses, written to
// android/app/src/main/assets/notices.json. npm entries carry the package's license text;
// Android (Maven) entries carry the license name and URL from the artifact's POM in the
// Gradle cache, and the license's text. Native entries (scripts/notices/native.json) are the
// C++ libraries React Native compiles into libreactnative.so, and the NDK's libc++. --check regenerates in memory and exits 1 if the committed asset differs.
// The output has no timestamp, so the same tree gives the same bytes.
import {execFileSync} from 'node:child_process';
import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {homedir} from 'node:os';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const OUT = join(ROOT, 'android/app/src/main/assets/notices.json');
const NOTICES = join(ROOT, 'scripts/notices');
const MAX_TEXT = 20_000;

// F-Droid's builder sets its own GRADLE_USER_HOME; the POMs live wherever Gradle put them.
export function gradleCache(env = process.env) {
  return join(env.GRADLE_USER_HOME ?? join(homedir(), '.gradle'), 'caches/modules-2/files-2.1');
}

// License names as POMs and npm write them, to an SPDX id with a text in scripts/notices/spdx.
// "BSD License" and the like name no single license: those need a checked OVERRIDES entry.
const SPDX_NAMES = [
  [/^(the )?apache (software )?license,? version 2\.0$/i, 'Apache-2.0'],
  [/^apache-2(\.0)?$/i, 'Apache-2.0'],
  [/^mit( license)?$/i, 'MIT'],
];

export function spdxFor(license) {
  for (const part of license.replace(/[()]/g, '').split(/ OR /)) {
    const name = part.trim();
    const hit = SPDX_NAMES.find(([re]) => re.test(name));
    const id = hit ? hit[1] : name;
    if (existsSync(join(NOTICES, 'spdx', `${id}.txt`))) return id;
  }
  return null;
}

function spdxText(id) {
  return readFileSync(join(NOTICES, 'spdx', `${id}.txt`), 'utf8');
}

export function androidText(name, license) {
  const own = OVERRIDES[name]?.text;
  if (own) return readFileSync(join(NOTICES, own), 'utf8');
  const id = spdxFor(license);
  return id ? spdxText(id) : undefined;
}

export function nativeNotices() {
  return JSON.parse(readFileSync(join(NOTICES, 'native.json'), 'utf8')).map(n => ({
    ...n,
    text: readFileSync(join(NOTICES, n.text), 'utf8').slice(0, MAX_TEXT),
  }));
}

// Artifacts whose POM is absent from the cache or names no license. Each entry was checked by
// hand against the project's published license; add one only with that check.
const OVERRIDES = {
  // Its POM inherits the license from guava-parent 26.0-android (checked 2026-10-02).
  'com.google.guava:listenablefuture': {
    name: 'The Apache Software License, Version 2.0',
    url: 'http://www.apache.org/licenses/LICENSE-2.0.txt',
  },
  // Its POM says only "BSD License"; 1.4.0's LICENSE is Facebook's BSD text (checked 2026-10-03;
  // later versions are MIT).
  'com.parse.bolts:bolts-tasks': {
    name: 'BSD License',
    url: 'https://github.com/BoltsFramework/Bolts-Android/blob/1.4.0/LICENSE',
    text: 'texts/bolts-android-LICENSE.txt',
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
  const dir = join(gradleCache(), g, a, v);
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
    const license = lic.map(l => l.name).join(' OR ');
    const text = androidText(name, license);
    if (!text) {
      missing.push(`${coord} (no text for "${license}")`);
      continue;
    }
    out.push({name, version: v, license, url: lic[0].url, text});
  }
  if (missing.length) {
    console.error(`no license found for ${missing.length} Android artifact(s); add a checked OVERRIDES entry:`);
    for (const m of missing) console.error(`  ${m}`);
    process.exit(1);
  }
  return out;
}

function npmNotices() {
  // A pinned devDependency, not npx: F-Droid builds offline.
  const raw = execFileSync(join(ROOT, 'node_modules/.bin/license-checker-rseidelsohn'), ['--production',
    '--excludePrivatePackages', '--json'], {cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024});
  return Object.entries(JSON.parse(raw)).map(([id, info]) => {
    const at = id.lastIndexOf('@');
    let text;
    const license = [].concat(info.licenses ?? 'UNKNOWN').join(' OR ');
    if (info.licenseFile && existsSync(info.licenseFile) && !/readme/i.test(info.licenseFile)) {
      text = readFileSync(info.licenseFile, 'utf8').slice(0, MAX_TEXT);
    } else if (spdxFor(license)) {
      text = `(The standard ${spdxFor(license)} text; the package ships no license file.)\n\n${spdxText(spdxFor(license))}`;
    }
    return {
      name: id.slice(0, at),
      version: id.slice(at + 1),
      license,
      ...(text ? {text} : {}),
    };
  }).sort((x, y) => x.name.localeCompare(y.name) || x.version.localeCompare(y.version));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const json = JSON.stringify({npm: npmNotices(), android: androidNotices(), native: nativeNotices()}, null, 1) + '\n';
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
    console.log(`wrote ${d.npm.length} npm, ${d.android.length} Android and ${d.native.length} native notices`);
  }
}
