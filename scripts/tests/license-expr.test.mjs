// R-M13 / AGENTS.md 14: the license gate must fail closed on anything that is not provably OSI.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {allowed} from '../license-expr.mjs';

test('plain OSI licenses pass', () => {
  for (const l of ['MIT', 'ISC', 'Apache-2.0', 'BSD-3-Clause']) assert.equal(allowed(l), true, l);
});

test('OR passes when any side is OSI, AND only when every side is', () => {
  assert.equal(allowed('MIT OR Apache-2.0'), true);
  assert.equal(allowed('MIT OR BUSL-1.1'), true);
  assert.equal(allowed('MIT AND ISC'), true);
  assert.equal(allowed('MIT AND BUSL-1.1'), false);
});

test('a non-OSI term ANDed with a parenthesised OR fails', () => {
  assert.equal(allowed('BUSL-1.1 AND (MIT OR Apache-2.0)'), false);
  assert.equal(allowed('SEE LICENSE IN LICENSE AND (MIT OR ISC)'), false);
});

test('parenthesised groups are evaluated, not flattened', () => {
  assert.equal(allowed('(MIT OR BUSL-1.1) AND ISC'), true);
  assert.equal(allowed('(MIT AND BUSL-1.1) OR ISC'), true);
  assert.equal(allowed('(MIT AND BUSL-1.1) OR CC-BY-4.0'), false);
});

test('unknown, guessed and malformed expressions fail closed', () => {
  for (const l of ['UNKNOWN', 'MIT*', 'Custom: LICENSE', '(MIT', 'MIT OR', '']) {
    assert.equal(allowed(l), false, JSON.stringify(l));
  }
});
