import assert from 'node:assert/strict';
import test from 'node:test';
import { getYouTubeDrawerGuidance, getYouTubeFeedReadiness, hasAllRequiredYouTubeSignals } from '../src/features/protection/youtube-readiness.ts';
import { getFeedPresentation } from '../src/features/protection/feed-presentation.ts';

const status = {
  available: true,
  serviceEnabled: true,
  protectionEnabled: true,
  observationMode: false,
  shortsEnabled: true,
  youtubeHomeEnabled: true,
  youtubeHomeObserved: false,
  youtubeHomeDetectionSupported: false,
  lastDetectionAt: 1,
  appAvailability: { youtube: 'installed', instagram: 'absent', x: 'absent' },
};

test('YouTube Home readiness is independent from Shorts', () => {
  assert.equal(getYouTubeFeedReadiness(status, 'shorts'), 'ready');
  assert.equal(getYouTubeFeedReadiness(status, 'home'), 'awaiting');
  assert.deepEqual(getYouTubeDrawerGuidance(status), ['home']);
  assert.equal(getFeedPresentation(status, 'shorts').statusLabel, 'Limited');
  assert.equal(getFeedPresentation(status, 'youtubeHome').statusLabel, 'Unavailable');
});

test('unknown Home layouts never produce a blocking claim', () => {
  const pending = getFeedPresentation(status, 'youtubeHome');
  assert.match(pending.detail, /no Home action will run/);
  assert.equal(getFeedPresentation({ ...status, youtubeHomeEnabled: false }, 'youtubeHome').statusLabel, 'Allowed');
});

test('an observed Home signal does not make Shorts ready', () => {
  const inverse = { ...status, youtubeHomeObserved: true, lastDetectionAt: 0, observationMode: true };
  assert.equal(getYouTubeFeedReadiness(inverse, 'home'), 'ready');
  assert.equal(getYouTubeFeedReadiness(inverse, 'shorts'), 'awaiting');
});

test('Start requires every enabled YouTube feed to have a supported observed signal', () => {
  assert.equal(hasAllRequiredYouTubeSignals(status), false, 'unsupported Home keeps mixed setup pending');
  assert.equal(hasAllRequiredYouTubeSignals({ ...status, shortsEnabled: false }), false, 'disabled Shorts is not evidence for Home');
  assert.equal(hasAllRequiredYouTubeSignals({ ...status, youtubeHomeEnabled: false }), true, 'observed enabled Shorts can start');
  assert.equal(hasAllRequiredYouTubeSignals({
    ...status,
    shortsEnabled: false,
    youtubeHomeDetectionSupported: true,
    youtubeHomeObserved: true,
  }), true, 'a supported observed enabled Home rule can start');
  assert.equal(hasAllRequiredYouTubeSignals({ ...status, shortsEnabled: false, youtubeHomeEnabled: false }), false, 'no enabled rule cannot start');
});

test('supported Home stays in setup until the user starts protection', () => {
  const observed = { ...status, youtubeHomeDetectionSupported: true, youtubeHomeObserved: true };
  const setup = getFeedPresentation({ ...observed, observationMode: true }, 'youtubeHome');
  assert.equal(setup.statusLabel, 'Set up');
  assert.match(setup.detail, /Setup only checks the screen/);
  const active = getFeedPresentation(observed, 'youtubeHome');
  assert.equal(active.statusLabel, 'Limited');
  assert.match(active.detail, /Subscriptions after 10 seconds/);
  assert.match(active.detail, /Unknown layouts stay available/);
});

test('supported unobserved Home requests a real signal without claiming enforcement', () => {
  const pending = getFeedPresentation({ ...status, youtubeHomeDetectionSupported: true }, 'youtubeHome');
  assert.equal(pending.statusLabel, 'Check');
  assert.match(pending.detail, /Open Home once/);
  assert.match(pending.detail, /layout is unsupported/);
});
