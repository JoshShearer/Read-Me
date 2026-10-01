// AGENTS.md 3 / R-M09.4: item content stays app-private. On API 31+ allowBackup="false" alone
// does not stop device-to-device transfer, so the manifest must also point at extraction rules
// that exclude every domain from both cloud backup and device transfer.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const manifest = readFileSync('android/app/src/main/AndroidManifest.xml', 'utf8');
const DOMAINS = ['root', 'file', 'database', 'sharedpref', 'external'];

test('allowBackup is false', () => {
  assert.match(manifest, /android:allowBackup="false"/);
});

test('the manifest points at data extraction rules', () => {
  assert.match(manifest, /android:dataExtractionRules="@xml\/data_extraction_rules"/);
});

test('every domain is excluded from cloud backup and device transfer', () => {
  const rules = readFileSync('android/app/src/main/res/xml/data_extraction_rules.xml', 'utf8');
  for (const section of ['cloud-backup', 'device-transfer']) {
    const body = rules.match(new RegExp(`<${section}[^>]*>([\\s\\S]*?)</${section}>`));
    assert.ok(body, `missing <${section}>`);
    for (const d of DOMAINS) assert.match(body[1], new RegExp(`<exclude domain="${d}" path="\\."\\s*/>`), `${section} ${d}`);
    assert.doesNotMatch(body[1], /<include/, `${section} must not include anything`);
  }
});

test('the TTS engine query is declared (R-M06)', () => {
  assert.match(manifest, /<action android:name="android.intent.action.TTS_SERVICE"\s*\/>/);
});
