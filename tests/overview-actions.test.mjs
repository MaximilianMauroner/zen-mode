import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { parse } from '@babel/parser';
import { getOverviewAction } from '../src/features/protection/overview-actions.ts';

const overviewSource = readFileSync(new URL('../src/app/(controls)/index.tsx', import.meta.url), 'utf8');

function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  visit(node);
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) value.forEach((child) => walk(child, visit));
    else if (value && typeof value === 'object' && value.type) walk(value, visit);
  }
}

function getSetUpXReturn() {
  const ast = parse(overviewSource, { sourceType: 'module', plugins: ['typescript', 'jsx'] });
  const matches = [];
  walk(ast, (node) => {
    if (node.type !== 'SwitchCase' || node.test?.type !== 'StringLiteral' || node.test.value !== 'set-up-x') return;
    const returned = node.consequent.find((statement) => statement.type === 'ReturnStatement');
    assert.ok(returned?.argument?.type === 'ObjectExpression', 'set-up-x must return an action object');
    matches.push(returned.argument);
  });
  assert.equal(matches.length, 1, 'expected exactly one set-up-x dispatcher case');
  return matches[0];
}

function getObjectProperty(object, name) {
  return object.properties.find((property) => property.type === 'ObjectProperty' && property.key.type === 'Identifier' && property.key.name === name);
}

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
  appAvailability: { youtube: 'installed', instagram: 'installed', x: 'installed' },
};

test('the overview is wired to the shared action selector', () => {
  assert.match(overviewSource, /getOverviewAction\(status, Boolean\(readError\)\)/);
  const action = getSetUpXReturn();
  assert.equal(getObjectProperty(action, 'title')?.value.value, 'Set up X');
  const run = getObjectProperty(action, 'run')?.value;
  assert.equal(run?.type, 'ArrowFunctionExpression');
  const calls = [];
  walk(run, (node) => {
    if (node.type === 'CallExpression' && node.callee.type === 'Identifier') calls.push(node);
  });
  assert.equal(calls.length, 1, 'set-up-x must dispatch exactly one operation');
  assert.equal(calls[0].callee.name, 'setDrawer');
  assert.equal(calls[0].arguments.length, 1);
  assert.equal(calls[0].arguments[0].type, 'StringLiteral');
  assert.equal(calls[0].arguments[0].value, 'x');
  assert.doesNotMatch(overviewSource, /status\.xObservationMode\) return \{ title: 'Set up X'/);
});

test('initial X setup offers the drawer without changing observation state', () => {
  assert.equal(getOverviewAction({ ...base, xObservationMode: true, xSignalMask: 0 }, false), 'set-up-x');
});

test('Videos ready then Home enabled and unobserved keeps a setup action', () => {
  assert.equal(getOverviewAction({ ...base, xSignalMask: 2 }, false), 'set-up-x');
});

test('disabled unobserved feeds do not create prompts', () => {
  assert.equal(getOverviewAction({ ...base, xHomeEnabled: false, xSignalMask: 2 }, false), null);
  assert.equal(getOverviewAction({ ...base, xVideosEnabled: false, xSignalMask: 1 }, false), null);
  assert.equal(getOverviewAction({ ...base, xHomeEnabled: false, xVideosEnabled: false, xSignalMask: 0 }, false), null);
});

test('unsupported Home-only YouTube does not hide actionable downstream setup', () => {
  const homeOnly = {
    ...base,
    shortsEnabled: false,
    youtubeHomeEnabled: true,
    youtubeHomeObserved: false,
    youtubeHomeDetectionSupported: false,
    xSignalMask: 1,
  };
  assert.equal(getOverviewAction(homeOnly, false), 'set-up-x');
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
