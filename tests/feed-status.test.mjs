import assert from 'node:assert/strict';
import test from 'node:test';
import { getFeedStatus } from '../src/features/protection/feed-status.ts';

const running = {
  shortsEnabled: true, xHomeEnabled: true, xVideosEnabled: true, xObservationMode: false, xHomeMinutes: 5,
  available: true,
  serviceEnabled: true,
  protectionEnabled: true,
  observationMode: false,
  instagramObservationMode: false,
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

test('both feed detectors must finish setup before the overview claims protection', () => {
  assert.equal(getFeedStatus({ ...running, observationMode: true }), 'setup');
  assert.equal(getFeedStatus({ ...running, instagramObservationMode: true }), 'setup');
  assert.equal(getFeedStatus({ ...running, observationMode: true, instagramObservationMode: true }), 'setup');
  assert.equal(getFeedStatus(running), 'active');
});

test('X setup counts only when a rule is enabled; disabled Shorts need no setup', () => {
  assert.equal(getFeedStatus({ ...running, xObservationMode: true }), 'setup');
  assert.equal(getFeedStatus({ ...running, xObservationMode: true, xHomeEnabled: false, xVideosEnabled: false }), 'active');
  assert.equal(getFeedStatus({ ...running, observationMode: true, shortsEnabled: false }), 'active');
});
