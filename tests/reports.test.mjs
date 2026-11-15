import assert from 'node:assert/strict';
import test from 'node:test';
import { checkReport, formatReport } from '../src/features/feedback/reports.ts';

const constants = { expoConfig: { version: '0.1.5', android: { versionCode: 8 }, extra: { id: 'private-id' } }, installationId: 'private-installation' };
const platform = { OS: 'android', Version: 35, constants: { Release: '15', Serial: 'private-serial' } };
const draft = (change = {}) => ({ kind: 'bug', message: '  The rule did not run.  ', details: '', contactEmail: '', ...change });

test('a report contains the text and version, without device identifiers', () => {
  const text = formatReport(draft({ details: ' Open the feed. ', contactEmail: ' me@example.com ' }), { constants, platform });
  assert.equal(text, 'Problem\n\nThe rule did not run.\n\nHow to repeat it\nOpen the feed.\n\nEmail for a reply\nme@example.com\n\nZen Mode 0.1.5 (8) · android 15');
  assert.equal(text.includes('private-'), false);
});

test('an idea uses its own label and omits empty optional fields', () => {
  const text = formatReport(draft({ kind: 'feature', message: 'Add a timer', details: '  ', contactEmail: '' }), { constants, platform });
  assert.equal(text, 'Idea\n\nAdd a timer\n\nZen Mode 0.1.5 (8) · android 15');
});

test('validation rejects invalid text and email before opening the share sheet', () => {
  for (const [change, expected] of [
    [{ message: '  ' }, 'empty_message'],
    [{ message: 'x'.repeat(4001) }, 'message_too_long'],
    [{ details: 'x'.repeat(4001) }, 'details_too_long'],
    [{ contactEmail: 'not-an-email' }, 'invalid_email'],
    [{ contactEmail: `${'a'.repeat(309)}@example.com` }, 'invalid_email'],
  ]) assert.equal(checkReport(draft(change)), expected);
  assert.equal(checkReport(draft({ message: 'x'.repeat(4000) })), null);
});
