import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { buildFeedbackIssueUrl } from '../src/features/feedback/github-issue-url.ts';

test('feedback opens an editable issue draft for this repository', () => {
  const url = new URL(buildFeedbackIssueUrl({ version: '1.0.3', platform: 'Android' }));

  assert.equal(url.origin, 'https://github.com');
  assert.equal(url.pathname, '/MaximilianMauroner/zen-mode/issues/new');
  assert.equal(url.searchParams.get('title'), '[Feedback] ');
  assert.match(url.searchParams.get('body'), /Zen Mode version: 1\.0\.3/);
  assert.match(url.searchParams.get('body'), /Platform: Android/);
  assert.equal(url.searchParams.has('labels'), false);
  assert.equal(url.searchParams.has('assignee'), false);
});

test('feedback details are encoded without changing their text', () => {
  const version = '1.0.3 beta+local';
  const platform = 'Android & web';
  const url = new URL(buildFeedbackIssueUrl({ version, platform }));
  const body = url.searchParams.get('body');

  assert.match(body, new RegExp(`Zen Mode version: ${version.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
  assert.match(body, new RegExp(`Platform: ${platform}`));
});

test('privacy copy discloses GitHub metadata and public submissions', () => {
  const privacyScreen = readFileSync(new URL('../src/app/privacy.tsx', import.meta.url), 'utf8');

  assert.match(privacyScreen, /sends the app version and platform to GitHub/);
  assert.match(privacyScreen, /Submitted issues and their contents are public/);
  assert.match(privacyScreen, /support@lab4code\.com/);
});
