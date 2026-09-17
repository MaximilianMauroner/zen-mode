import assert from 'node:assert/strict';
import test from 'node:test';
import { getFeedPresentation } from '../src/features/protection/feed-presentation.ts';

const active = {
  shortsEnabled: true, xHomeEnabled: true, xVideosEnabled: true, xObservationMode: false, xHomeMinutes: 5,
  // Both X surfaces have been observed, which is what setup leaves behind.
  xSignalMask: 3,
  available: true, serviceEnabled: true, protectionEnabled: true,
  observationMode: false, instagramObservationMode: false,
  instagramWaitSeconds: 15, instagramReelsMinutes: 1,
  instagramHomeMinutes: 5, instagramExploreBlocked: true,
  lastDetectionAt: 1, instagramSignalMask: 3,
  appAvailability: { youtube: 'installed', instagram: 'installed', x: 'installed' },
  browserAvailability: { chrome: 'installed', samsungInternet: 'installed', opera: 'installed', firefox: 'installed' },
};

test('labels distinguish blocked, limited and deliberately allowed feeds', () => {
  assert.equal(getFeedPresentation(active, 'shorts').statusLabel, 'Limited');
  assert.equal(getFeedPresentation(active, 'reels').statusLabel, 'Limited');
  assert.equal(getFeedPresentation(active, 'home').statusLabel, 'Limited');
  assert.equal(getFeedPresentation(active, 'explore').statusLabel, 'Blocked');
  assert.equal(getFeedPresentation({ ...active, instagramExploreBlocked: false }, 'explore').statusLabel, 'Allowed');
});

test('saved rules never claim to be running without access, protection or feed setup', () => {
  for (const override of [{ serviceEnabled: false }, { protectionEnabled: false }, { instagramObservationMode: true }]) {
    for (const feed of ['reels', 'home', 'explore']) {
      const result = getFeedPresentation({ ...active, ...override }, feed);
      assert.equal(result.statusLabel, 'Not running');
      assert.match(result.detail, /^Saved:/);
    }
  }
  assert.equal(getFeedPresentation({ ...active, observationMode: true }, 'shorts').statusLabel, 'Not running');
  assert.equal(getFeedPresentation({ ...active, observationMode: true }, 'reels').statusLabel, 'Limited');
});

test('missing and unsupported status never implies blocking', () => {
  assert.equal(getFeedPresentation(null, 'shorts', true).statusLabel, 'Checking');
  assert.equal(getFeedPresentation(null, 'shorts').statusLabel, 'Unavailable');
  assert.equal(getFeedPresentation({ ...active, available: false }, 'explore').statusLabel, 'Unavailable');
});

test('disabled Shorts and X rules are explicitly allowed', () => {
  assert.equal(getFeedPresentation({ ...active, shortsEnabled: false }, 'shorts').statusLabel, 'Allowed');
  assert.equal(getFeedPresentation({ ...active, xHomeEnabled: false }, 'xHome').statusLabel, 'Allowed');
  assert.equal(getFeedPresentation({ ...active, xVideosEnabled: false }, 'xVideos').statusLabel, 'Allowed');
  assert.equal(getFeedPresentation({ ...active, xObservationMode: true }, 'xHome').statusLabel, 'Set up');
  assert.equal(getFeedPresentation({ ...active, protectionEnabled: false }, 'xVideos').statusLabel, 'Not running');
});

test('an observed X feed reports limited', () => {
  assert.equal(getFeedPresentation(active, 'xHome').statusLabel, 'Limited');
  assert.equal(getFeedPresentation(active, 'xVideos').statusLabel, 'Limited');
});

test('an X feed switched on after setup asks for its own signal', () => {
  // Home was observed during setup; Videos was switched on afterwards.
  const videosPending = { ...active, xSignalMask: 1 };
  const pending = getFeedPresentation(videosPending, 'xVideos');
  assert.equal(pending.statusLabel, 'Check');
  assert.match(pending.detail, /Open one video in X once to start/);

  // The point of the change: Home keeps reporting that it is running.
  assert.equal(getFeedPresentation(videosPending, 'xHome').statusLabel, 'Limited');
});

test('an unobserved X feed still reports the blocker that outranks it', () => {
  const videosPending = { ...active, xSignalMask: 1 };
  assert.equal(getFeedPresentation({ ...videosPending, serviceEnabled: false }, 'xVideos').statusLabel, 'Not running');
  assert.equal(getFeedPresentation({ ...videosPending, protectionEnabled: false }, 'xVideos').statusLabel, 'Not running');
  assert.equal(getFeedPresentation({ ...videosPending, xObservationMode: true }, 'xVideos').statusLabel, 'Set up');
});
