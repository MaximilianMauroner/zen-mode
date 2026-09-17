import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { getOverviewAction } from '../src/features/protection/overview-actions.ts';

const overviewSource = readFileSync(new URL('../src/app/(controls)/index.tsx', import.meta.url), 'utf8');

const base = {
  available: true,
  serviceEnabled: true,
  currentConsent: true,
  protectionEnabled: true,
  observationMode: false,
  shortsEnabled: false,
  xHomeEnabled: true,
  xVideosEnabled: true,
  xHomeMinutes: 5,
  xHomeUsedMs: 0,
  xHomeBreakRemainingMs: 0,
  xObservationMode: false,
  xSignalMask: 3,
  lastEventAt: 0,
  lastDetectionAt: 0,
  detectionCount: 0,
  lastDetectionReason: '',
  instagramObservationMode: false,
  instagramWaitSeconds: 30,
  instagramReelsMinutes: 5,
  instagramHomeMinutes: 5,
  instagramHomeUsedMs: 0,
  instagramHomeBreakRemainingMs: 0,
  instagramExploreBlocked: true,
  instagramLastDetectionAt: 0,
  instagramDetectionCount: 0,
  instagramSignalMask: 3,
  instagramLastDetectionReason: '',
  adultSiteEnabled: false,
  adultSiteCustomCount: 0,
  browserSignalMask: 0,
};

test('the overview is wired to the shared action selector', () => {
  assert.match(overviewSource, /getOverviewAction\(status, Boolean\(readError\)\)/);
  assert.match(overviewSource, /case 'set-up-x'/);
  assert.match(overviewSource, /setDrawer\('x'\)/);
  assert.doesNotMatch(overviewSource, /status\.xObservationMode\) return \{ title: 'Set up X'/);
  assert.doesNotMatch(overviewSource, /setXObservationMode|setXSettings/);
});

test('initial X setup offers the drawer without changing observation state', () => {
  assert.equal(getOverviewAction({ ...base, xObservationMode: true, xSignalMask: 0 }, false), 'set-up-x');
});

test('Home ready then Videos enabled and unobserved keeps a setup action', () => {
  assert.equal(getOverviewAction({ ...base, xSignalMask: 1 }, false), 'set-up-x');
});

test('Videos ready then Home enabled and unobserved keeps a setup action', () => {
  assert.equal(getOverviewAction({ ...base, xSignalMask: 2 }, false), 'set-up-x');
});

test('all enabled X feeds ready clear the setup action', () => {
  assert.equal(getOverviewAction(base, false), null);
});

test('disabled unobserved feeds do not create prompts', () => {
  assert.equal(getOverviewAction({ ...base, xHomeEnabled: false, xSignalMask: 2 }, false), null);
  assert.equal(getOverviewAction({ ...base, xVideosEnabled: false, xSignalMask: 1 }, false), null);
  assert.equal(getOverviewAction({ ...base, xHomeEnabled: false, xVideosEnabled: false, xSignalMask: 0 }, false), null);
});

test('fresh native signal refresh clears the prompt', () => {
  const awaitingVideos = { ...base, xSignalMask: 1 };
  assert.equal(getOverviewAction(awaitingVideos, false), 'set-up-x');
  assert.equal(getOverviewAction({ ...awaitingVideos, xSignalMask: 3 }, false), null);
});

test('higher-priority error, permission, consent, and protection states win', () => {
  assert.equal(getOverviewAction(base, true), 'retry');
  assert.equal(getOverviewAction({ ...base, available: false }, false), null);
  assert.equal(getOverviewAction({ ...base, serviceEnabled: false }, false), 'open-accessibility');
  assert.equal(getOverviewAction({ ...base, currentConsent: false, protectionEnabled: false }, false), 'resume-protection');
  assert.equal(getOverviewAction({ ...base, protectionEnabled: false }, false), 'resume-protection');
});

test('existing Shorts and Instagram setup actions retain priority over X', () => {
  assert.equal(getOverviewAction({ ...base, shortsEnabled: true, observationMode: true, xSignalMask: 1 }, false), 'check-youtube');
  assert.equal(getOverviewAction({ ...base, instagramObservationMode: true, instagramSignalMask: 0, xSignalMask: 1 }, false), 'check-instagram');
});
