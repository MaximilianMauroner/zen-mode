import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { getXDrawerGuidance } from '../src/features/protection/x-drawer-guidance.ts';

const drawerSource = readFileSync(new URL('../src/components/ui/feed-controls-drawer.tsx', import.meta.url), 'utf8');

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

test('the drawer uses the shared guidance helper for both rendered sections', () => {
  assert.match(drawerSource, /getXDrawerGuidance\(status, Boolean\(observing\)\)/);
  const initialSection = drawerSource.slice(drawerSource.indexOf('{observing && enabled'), drawerSource.indexOf('{xGuidance.postSetupAwaiting.length'));
  assert.match(initialSection, /xGuidance\.initialAwaiting/);
  assert.doesNotMatch(initialSection, /xGuidance\.postSetupAwaiting/);
  const followUpSection = drawerSource.slice(drawerSource.indexOf('{xGuidance.postSetupAwaiting.length'));
  assert.match(followUpSection, /xGuidance\.postSetupAwaiting/);
});

test('initial observation shows every enabled feed still awaiting its signal', () => {
  assert.deepEqual(getXDrawerGuidance({ ...base, xSignalMask: 1 }, true), {
    initialAwaiting: ['videos'],
    postSetupAwaiting: [],
  });
});

test('initial observation shows no checklist items once all enabled feeds are ready', () => {
  assert.deepEqual(getXDrawerGuidance(base, true), {
    initialAwaiting: [],
    postSetupAwaiting: [],
  });
});

test('after setup, a Videos gap becomes follow-up guidance while Home stays ready', () => {
  assert.deepEqual(getXDrawerGuidance({ ...base, xSignalMask: 1 }, false), {
    initialAwaiting: ['videos'],
    postSetupAwaiting: ['videos'],
  });
});

test('after setup, the reverse Home gap is also follow-up guidance', () => {
  assert.deepEqual(getXDrawerGuidance({ ...base, xSignalMask: 2 }, false), {
    initialAwaiting: ['home'],
    postSetupAwaiting: ['home'],
  });
});

test('disabled unobserved feeds and no enabled feeds produce no guidance', () => {
  assert.deepEqual(getXDrawerGuidance({ ...base, xHomeEnabled: false, xSignalMask: 2 }, false), {
    initialAwaiting: [],
    postSetupAwaiting: [],
  });
  assert.deepEqual(getXDrawerGuidance({ ...base, xHomeEnabled: false, xVideosEnabled: false, xSignalMask: 0 }, true), {
    initialAwaiting: [],
    postSetupAwaiting: [],
  });
});

test('active observation never renders the later-enabled follow-up section', () => {
  assert.deepEqual(getXDrawerGuidance({ ...base, xSignalMask: 1 }, true).postSetupAwaiting, []);
});
