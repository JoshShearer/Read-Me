import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseCoordinates, parsePomLicenses, gradleCache, spdxFor, nativeNotices, androidText, compareNotices} from '../make-notices.mjs';

test('coordinates come from the dependency tree, resolved versions win, deduped', () => {
  const tree = `releaseRuntimeClasspath
+--- androidx.work:work-runtime-ktx:2.12.0
|    \\--- androidx.work:work-runtime:2.12.0
|         +--- androidx.core:core:1.9.0 -> 1.13.1 (*)
|         \\--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.2.0} -> 2.2.0 (c)
+--- project :react-native-safe-area-context
\\--- androidx.core:core:1.13.1`;
  assert.deepEqual(parseCoordinates(tree), [
    'androidx.core:core:1.13.1',
    'androidx.work:work-runtime-ktx:2.12.0',
    'androidx.work:work-runtime:2.12.0',
    'org.jetbrains.kotlin:kotlin-stdlib:2.2.0',
  ]);
});

test('licenses are read from a POM', () => {
  const pom = `<project><licenses><license><name>The Apache Software License, Version 2.0</name>
    <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses></project>`;
  assert.deepEqual(parsePomLicenses(pom), [
    {name: 'The Apache Software License, Version 2.0', url: 'https://www.apache.org/licenses/LICENSE-2.0.txt'},
  ]);
  assert.deepEqual(parsePomLicenses('<project/>'), []);
});

test('the Gradle cache honours GRADLE_USER_HOME', () => {
  assert.equal(gradleCache({GRADLE_USER_HOME: '/x/gh'}), '/x/gh/caches/modules-2/files-2.1');
  assert.match(gradleCache({}), /\.gradle\/caches\/modules-2\/files-2\.1$/);
});

test('POM and npm license names map to a shipped SPDX text', () => {
  assert.equal(spdxFor('The Apache Software License, Version 2.0'), 'Apache-2.0');
  assert.equal(spdxFor('APACHE-2'), 'Apache-2.0');
  assert.equal(spdxFor('Apache-2.0'), 'Apache-2.0');
  assert.equal(spdxFor('MIT license'), 'MIT');
  assert.equal(spdxFor('(MIT OR Apache-2.0)'), 'MIT');
  assert.equal(spdxFor('BSD-3-Clause'), 'BSD-3-Clause');
  assert.equal(spdxFor('BSD License'), null); // which BSD is unknown: needs a checked override
  assert.equal(spdxFor('Some Custom License'), null);
});

test('every native notice carries its license text', () => {
  const n = nativeNotices();
  assert.ok(n.length >= 7);
  for (const e of n) assert.ok(e.text && e.text.length > 200, `${e.name} has no text`);
});

test('a Maven entry gets the text of its license, or of its checked override', () => {
  assert.match(androidText('com.x:y', 'The Apache Software License, Version 2.0'), /Apache License/);
  assert.match(androidText('com.parse.bolts:bolts-tasks', 'BSD License'), /Bolts/);
  assert.equal(androidText('com.x:y', 'Some Custom License'), undefined);
});

test('substituted and versionless coordinates resolve to what Gradle picked', () => {
  const tree = `releaseRuntimeClasspath
|    +--- com.facebook.react:react-native:+ -> com.facebook.react:react-android:0.87.1
+--- com.facebook.react:react-android -> 0.87.1 (*)
\\--- com.facebook.react:hermes-android -> com.facebook.hermes:hermes-android:250829098.0.17`;
  assert.deepEqual(parseCoordinates(tree), [
    'com.facebook.hermes:hermes-android:250829098.0.17',
    'com.facebook.react:react-android:0.87.1',
  ]);
});

test('libjpeg-turbo, compiled into Fresco\'s imagetranscoder, ships its BSD and IJG notices', () => {
  const j = nativeNotices().find(n => n.name === 'libjpeg-turbo');
  assert.ok(j, 'no libjpeg-turbo notice');
  assert.match(j.text, /Independent JPEG Group/);
  assert.match(j.text, /Redistribution and use in source and binary forms/);
});

test('REA-50: {prefer} and {require} unwrap like {strictly}; the arrow still wins', () => {
  const tree = `releaseRuntimeClasspath
+--- a.b:prefer-only:{prefer 1.2.0}
+--- a.b:prefer-arrow:{prefer 1.2.0} -> 1.3.0
+--- a.b:require-only:{require 2.0}
+--- a.b:require-arrow:{require 2.0} -> 2.1 (c)
+--- a.b:require-reject:{require 1.0; reject 2.0} -> 1.1
\\--- a.b:strict:{strictly 3.0}`;
  assert.deepEqual(parseCoordinates(tree), [
    'a.b:prefer-arrow:1.3.0',
    'a.b:prefer-only:1.2.0',
    'a.b:require-arrow:2.1',
    'a.b:require-only:2.0',
    'a.b:require-reject:1.1',
    'a.b:strict:3.0',
  ]);
});

test('REA-50: a rich version that cannot be resolved fails loudly, naming the module', () => {
  assert.throws(
    () => parseCoordinates('+--- a.b:c:{require 1.0; reject 2.0}'),
    /a\.b:c/,
  );
  assert.throws(() => parseCoordinates('+--- a.b:d:{strictly [1.0, 2.0)}'), /a\.b:d/);
});

test('REA-50: npm notices sort by UTF-16 code unit, not by locale', () => {
  const names = ['string_decoder', 'string-width', 'String', 'éclair', 'zeta'];
  const sorted = names.map(name => ({name, version: '1.0.0'})).sort(compareNotices).map(n => n.name);
  assert.deepEqual(sorted, ['String', 'string-width', 'string_decoder', 'zeta', 'éclair']);
  assert.ok(compareNotices({name: 'a', version: '1.10.0'}, {name: 'a', version: '1.9.0'}) < 0);
  assert.equal(compareNotices({name: 'a', version: '1'}, {name: 'a', version: '1'}), 0);
});
