import assert from 'node:assert/strict';
import test from 'node:test';
import { getFeedStatus } from '../src/features/protection/feed-status.ts';

const running = {
  shortsEnabled: true, xHomeEnabled: true, xVideosEnabled: true, xObservationMode: false, xHomeMinutes: 5,
  // Both X surfaces have been observed, which is what setup leaves behind.
  xSignalMask: 3,
  lastDetectionAt: 1,
  available: true,
  serviceEnabled: true,
  protectionEnabled: true,
  observationMode: false,
  instagramObservationMode: false,
  instagramSignalMask: 3,
  instagramExploreBlocked: true,
  appAvailability: { youtube: 'installed', instagram: 'installed', x: 'installed' },
  browserAvailability: { chrome: 'installed', samsungInternet: 'installed', opera: 'installed', firefox: 'installed' },
};

test('an unreadable service never claims protection', () => {
  assert.equal(getFeedStatus(null), 'unknown');
});

test('unsupported platform wins over cached enabled switches', () => {
  assert.equal(getFeedStatus({ ...running, available: false }), 'unavailable');
});

test('revoked access wins over enabled protection and finished setup', () => {
  assert.equal(getFeedStatus({ ...running, serviceEnabled: false }), 'permission');
});

test('a paused guard never claims active feed rules', () => {
  assert.equal(getFeedStatus({ ...running, protectionEnabled: false }), 'paused');
});

test('a pending detector is shown as setup or partial without overclaiming', () => {
  assert.equal(getFeedStatus({ ...running, observationMode: true }), 'partial');
  assert.equal(getFeedStatus({ ...running, instagramObservationMode: true }), 'partial');
  assert.equal(getFeedStatus({ ...running, observationMode: true, instagramObservationMode: true }), 'partial');
  assert.equal(getFeedStatus(running), 'active');
});

test('X setup counts only when a rule is enabled; disabled Shorts need no setup', () => {
  assert.equal(getFeedStatus({ ...running, xObservationMode: true }), 'partial');
  assert.equal(getFeedStatus({ ...running, xObservationMode: true, xHomeEnabled: false, xVideosEnabled: false, xSignalMask: 0 }), 'active');
  assert.equal(getFeedStatus({ ...running, observationMode: true, shortsEnabled: false }), 'active');
});

test('an X feed still waiting for its own signal never reads as full protection', () => {
  // Videos switched on after setup, before the video pager has been seen.
  assert.equal(getFeedStatus({ ...running, xSignalMask: 1 }), 'partial');
  // Home switched on after setup, before the timeline has been seen.
  assert.equal(getFeedStatus({ ...running, xSignalMask: 2 }), 'partial');
  // A feed that is switched off is not waiting for anything.
  assert.equal(getFeedStatus({ ...running, xSignalMask: 1, xVideosEnabled: false }), 'active');
});
