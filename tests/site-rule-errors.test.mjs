import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { getAddDomainErrorMessage } from '../src/features/protection/site-rule-errors.ts';

const bridgeError = (detail) =>
  Object.assign(new Error(`Call to function 'ZenGuard.addBlockedDomain' has been rejected.\n→ Caused by: ${detail}`), {
    code: 'ERR_UNEXPECTED',
  });

const assertNoNativeDetails = (message) => {
  for (const leak of ['java.', 'ZenGuard', 'Exception', 'Caused by', 'rejected']) {
    assert.ok(!message.includes(leak), `"${message}" exposes "${leak}"`);
  }
};

test('a rejected domain shows the validation guidance without the bridge envelope', () => {
  const message = getAddDomainErrorMessage(
    bridgeError('java.lang.IllegalArgumentException: Local and IP addresses are not supported'),
  );
  assert.equal(message, 'Local and IP addresses are not supported.');
  assertNoNativeDetails(message);
});

test('an unknown native failure shows a plain message', () => {
  const message = getAddDomainErrorMessage(bridgeError('java.lang.IllegalStateException: disk full at com.example.Store'));
  assert.equal(message, 'That site could not be added.');
  assertNoNativeDetails(message);
  assert.equal(getAddDomainErrorMessage('not an error'), 'That site could not be added.');
});

test('every native domain validation message keeps its specific guidance', () => {
  const nativeDir = new URL('../modules/zen-guard/android/src/main/java/com/maxmauroner/zenguard/', import.meta.url);
  const source = ['AdultSitePolicy.kt', 'AdultSiteRuleStore.kt']
    .map((file) => readFileSync(new URL(file, nativeDir), 'utf8'))
    .join('\n');
  const messages = new Set(
    [...source.matchAll(/require\([\s\S]*?\)\s*\{\s*"([^"]+)"\s*\}|IllegalArgumentException\("([^"]+)"\)/g)].map(
      (match) => match[1] ?? match[2],
    ),
  );
  assert.ok(messages.size >= 8, `expected the native validation messages, found ${messages.size}`);
  for (const message of messages) {
    assert.equal(getAddDomainErrorMessage(bridgeError(`java.lang.IllegalArgumentException: ${message}`)), `${message}.`);
  }
});
