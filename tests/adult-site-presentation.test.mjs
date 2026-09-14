import assert from 'node:assert/strict';
import test from 'node:test';
import { getAdultSitePresentation } from '../src/features/protection/adult-site-presentation.ts';

const status = {
  available: true,
  serviceEnabled: true,
  protectionEnabled: true,
  adultSiteEnabled: true,
  adultSiteCustomCount: 0,
  browserSignalMask: 0,
};

test('website rules never claim blocking without runtime prerequisites', () => {
  assert.equal(getAdultSitePresentation({ ...status, available: false }).statusLabel, 'ANDROID');
  assert.equal(getAdultSitePresentation({ ...status, serviceEnabled: false }).statusLabel, 'SAVED');
  assert.equal(getAdultSitePresentation({ ...status, protectionEnabled: false }).statusLabel, 'SAVED');
  assert.equal(getAdultSitePresentation({ ...status, adultSiteEnabled: false }).statusLabel, 'OFF');
});

test('enabled rules require an observed browser before claiming active blocking', () => {
  assert.equal(getAdultSitePresentation(status).statusLabel, 'CHECK');
  assert.deepEqual(getAdultSitePresentation({ ...status, browserSignalMask: 3, adultSiteCustomCount: 2 }), {
    detail: 'Blocking in 2 checked browsers · 2 added.',
    statusLabel: 'ON',
    tone: 'accent',
  });
});

test('missing status stays unknown', () => {
  assert.equal(getAdultSitePresentation(null, true).detail, 'Checking the website rule.');
  assert.equal(getAdultSitePresentation(null, false).detail, 'Website status unavailable.');
});
