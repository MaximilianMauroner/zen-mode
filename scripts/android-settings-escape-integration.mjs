#!/usr/bin/env node

import assert from 'node:assert/strict';
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const androidHome = process.env.ANDROID_HOME ?? '/home/codex/android-sdk';
const adbBinary = process.env.ADB ?? resolve(androidHome, 'platform-tools/adb');
const serial = process.env.ZEN_GUARD_TEST_SERIAL;
const dataResetApproval = process.env.ZEN_GUARD_ALLOW_TASK_DATA_RESET;
const taskAvdName = 'moodqa';
const metroUrl = process.env.ZEN_GUARD_METRO_URL ?? 'http://10.0.2.2:8081';
const appPackage = 'com.lab4code.zenmode';
const serviceComponent = `${appPackage}/com.maxmauroner.zenguard.ZenGuardAccessibilityService`;
const settingsPackage = 'com.android.settings';
const clockPackage = 'com.google.android.deskclock';
const clockActivity = `${clockPackage}/com.android.deskclock.DeskClock`;
const launcherPackage = 'com.google.android.apps.nexuslauncher';
const uiDumpPath = '/sdcard/zen-guard-settings-safety.xml';
const debugApkPath = resolve(root, 'android/app/build/outputs/apk/debug/app-debug.apk');
const evidenceLogPath = process.env.ZEN_GUARD_EVIDENCE_LOG
  ? resolve(root, process.env.ZEN_GUARD_EVIDENCE_LOG)
  : null;
let evidenceTranscript = '';

const sleep = (milliseconds) => new Promise((resolveSleep) => setTimeout(resolveSleep, milliseconds));

function run(command, args, { input, allowFailure = false, quiet = false } = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: 'utf8',
    input,
    maxBuffer: 16 * 1024 * 1024,
  });
  if (!allowFailure && result.status !== 0) {
    throw new Error([
      `Command failed (${result.status}): ${command} ${args.join(' ')}`,
      result.stdout,
      result.stderr,
    ].filter(Boolean).join('\n'));
  }
  if (!quiet && result.stderr?.trim()) process.stderr.write(result.stderr);
  return result.stdout ?? '';
}

function adb(...args) {
  return run(adbBinary, ['-s', serial, ...args], { quiet: true });
}

function adbMaybe(...args) {
  return run(adbBinary, ['-s', serial, ...args], { allowFailure: true, quiet: true });
}

function shell(...args) {
  return adb('shell', ...args);
}

function pass(message) {
  const line = `${new Date().toISOString()} PASS ${message}\n`;
  evidenceTranscript += line;
  process.stdout.write(line);
}

function step(message) {
  const line = `\n${new Date().toISOString()} ## ${message}\n`;
  evidenceTranscript += line;
  process.stdout.write(line);
}

function writeEvidence() {
  if (evidenceLogPath) writeFileSync(evidenceLogPath, evidenceTranscript);
}

async function waitFor(description, predicate, timeoutMs = 20_000, intervalMs = 250) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const result = await predicate();
      if (result) return result;
    } catch (error) {
      lastError = error;
    }
    await sleep(intervalMs);
  }
  throw new Error(`Timed out waiting for ${description}${lastError ? `: ${lastError.message}` : ''}`);
}

function decodeXml(value) {
  return value
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&amp;', '&');
}

function parseUiNodes(xml) {
  return [...xml.matchAll(/<node\b[^>]*\/?\s*>/g)].map(([tag]) => {
    const attributes = {};
    for (const match of tag.matchAll(/([\w:-]+)="([^"]*)"/g)) {
      attributes[match[1]] = decodeXml(match[2]);
    }
    return attributes;
  });
}

function dumpUi() {
  adbMaybe('shell', 'uiautomator', 'dump', uiDumpPath);
  const xml = adb('exec-out', 'cat', uiDumpPath);
  assert.match(xml, /<hierarchy\b/, 'UI Automator did not return a hierarchy');
  return { xml, nodes: parseUiNodes(xml) };
}

function matchingNode(attribute, value, exact = true) {
  const { nodes } = dumpUi();
  return nodes.find((node) => exact ? node[attribute] === value : node[attribute]?.includes(value));
}

async function waitForNode(attribute, value, { exact = true, timeoutMs = 20_000 } = {}) {
  return waitFor(`${attribute}=${JSON.stringify(value)}`, () => matchingNode(attribute, value, exact), timeoutMs);
}

function tapBounds(bounds) {
  const match = /^\[(\d+),(\d+)]\[(\d+),(\d+)]$/.exec(bounds ?? '');
  assert.ok(match, `Node has unreadable bounds: ${bounds}`);
  const [, left, top, right, bottom] = match.map(Number);
  shell('input', 'tap', String(Math.floor((left + right) / 2)), String(Math.floor((top + bottom) / 2)));
}

async function tapNode(attribute, value, options) {
  const node = await waitForNode(attribute, value, options);
  tapBounds(node.bounds);
}

function serviceIsEnabled() {
  return shell('settings', 'get', 'secure', 'enabled_accessibility_services').includes(serviceComponent);
}

function serviceIsBound() {
  return shell('dumpsys', 'accessibility').includes(serviceComponent);
}

function currentFocus() {
  const windows = shell('dumpsys', 'window');
  const current = windows.split('\n').find((line) => line.includes('mCurrentFocus='))?.trim() ?? '';
  if (current && !current.includes('mCurrentFocus=null')) return current;
  const focusedApp = windows.split('\n').find((line) => line.includes('mFocusedApp='))?.trim() ?? '';
  return [current, focusedApp].filter(Boolean).join(' | ');
}

function parseWindowRecords(windows) {
  return windows.split(/(?=^  Window #\d+ Window\{)/m).map((record) => {
    const index = Number(/^  Window #(\d+)/m.exec(record)?.[1]);
    const ownerPackage = /\bpackage=([^\s]+)/.exec(record)?.[1];
    const type = /\bty=([^\s]+)/.exec(record)?.[1];
    return {
      index,
      ownerPackage,
      type,
      isOnScreen: /\bisOnScreen=true\b/.test(record),
      isVisible: /\bisVisible=true\b/.test(record),
      record,
    };
  }).filter((record) => Number.isInteger(record.index));
}

function visibleWindowEvidence(record) {
  return `#${record.index} owner=${record.ownerPackage} type=${record.type} isOnScreen=${record.isOnScreen} isVisible=${record.isVisible}`;
}

async function waitForFocus(packageName, timeoutMs = 10_000) {
  return waitFor(`foreground package ${packageName}`, () => {
    const focus = currentFocus();
    return focus.includes(packageName) ? focus : false;
  }, timeoutMs, 150);
}

function startAction(action) {
  adb('shell', 'am', 'start', '-W', '-a', action);
}

function startActionAsync(action) {
  return spawn(adbBinary, ['-s', serial, 'shell', 'am', 'start', '-a', action], {
    cwd: root,
    stdio: 'ignore',
  });
}

async function startActionAndWait(action) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    startAction(action);
    const focused = await waitForFocus(settingsPackage, 4_000).catch(() => null);
    if (focused) return focused;
    await sleep(500);
  }
  throw new Error(`Could not bring ${action} to the foreground`);
}

function startComponent(component) {
  adb('shell', 'am', 'start', '-W', '-n', component);
}

async function assertSettingsStaysForeground(action, label, observeTransition = () => {}) {
  const settingsLaunch = startActionAsync(action);
  const observed = [];
  await waitFor(`${label} first foreground frame after its single launch`, () => {
    const windows = shell('dumpsys', 'window', 'windows');
    observeTransition(windows);
    const focus = currentFocus();
    observed.push(focus);
    return focus.includes(settingsPackage) ? focus : false;
  }, 10_000, 50);
  for (let index = 0; index < 40; index += 1) {
    const windows = shell('dumpsys', 'window', 'windows');
    observeTransition(windows);
    const focus = currentFocus();
    observed.push(focus);
    assert.ok(
      focus.includes(settingsPackage),
      `${label} was ejected on poll ${index + 1}: ${focus}\n${observed.join('\n')}`,
    );
    await sleep(200);
  }
  settingsLaunch.unref();
  const ui = dumpUi();
  assert.ok(ui.nodes.some((node) => node.package === settingsPackage), `${label} UI is not owned by Settings`);
  assert.doesNotMatch(ui.xml, /CHECK IN FIRST|BETWEEN VISITS|Start \d+ min|Not now/);
  pass(`${label} stayed foreground continuously for 8 seconds after one Settings launch`);
}

function privateFile(path) {
  return adb('exec-out', 'run-as', appPackage, 'cat', path);
}

function privateSha256(path) {
  return shell('run-as', appPackage, 'sha256sum', path).trim().split(/\s+/)[0];
}

function writePrivatePreference(fileName, xml) {
  const token = `${process.pid}-${fileName}`;
  const localPath = resolve(tmpdir(), `zen-guard-${token}.xml`);
  const devicePath = `/data/local/tmp/zen-guard-${token}.xml`;
  writeFileSync(localPath, xml);
  try {
    adb('push', localPath, devicePath);
    shell('run-as', appPackage, 'mkdir', '-p', 'shared_prefs');
    shell('run-as', appPackage, 'cp', devicePath, `shared_prefs/${fileName}.xml`);
    shell('run-as', appPackage, 'chmod', '660', `shared_prefs/${fileName}.xml`);
  } finally {
    adbMaybe('shell', 'rm', '-f', devicePath);
    rmSync(localPath, { force: true });
  }
  const stored = privateFile(`shared_prefs/${fileName}.xml`);
  assert.equal(stored, xml, `${fileName} was not stored byte-for-byte`);
}

function xmlEscape(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function preferenceXml(entries) {
  const body = entries.map(({ kind, name, value }) => {
    if (kind === 'string') return `    <string name="${name}">${xmlEscape(value)}</string>`;
    return `    <${kind} name="${name}" value="${value}" />`;
  }).join('\n');
  return `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n${body}${body ? '\n' : ''}</map>\n`;
}

function emptyPreferenceXml() {
  return preferenceXml([]);
}

function seedScenario(mode, day, nowWallMs) {
  const dailyEntries = [];
  const intentEntries = [];
  const rollingEntries = [];

  if (mode === 'daily') {
    dailyEntries.push(
      { kind: 'string', name: 'limits', value: JSON.stringify({ [settingsPackage]: 1, [clockPackage]: 1 }) },
      { kind: 'string', name: 'usage', value: JSON.stringify({ [settingsPackage]: 60_000, [clockPackage]: 60_000 }) },
      { kind: 'int', name: 'usage_day', value: day },
    );
  } else if (mode === 'timed-visit') {
    intentEntries.push({
      kind: 'string',
      name: 'intents',
      value: JSON.stringify({
        [settingsPackage]: { session: 1, cooldown: 1 },
        [clockPackage]: { session: 1, cooldown: 1 },
      }),
    });
  } else if (mode === 'rolling') {
    rollingEntries.push(
      {
        kind: 'string',
        name: 'rules',
        value: JSON.stringify({
          [settingsPackage]: { allowance: 1, window: 15 },
          [clockPackage]: { allowance: 1, window: 15 },
        }),
      },
      {
        kind: 'string',
        name: 'usage',
        value: JSON.stringify({
          [settingsPackage]: [[nowWallMs, 60_000]],
          [clockPackage]: [[nowWallMs, 60_000]],
        }),
      },
    );
  } else {
    throw new Error(`Unknown scenario: ${mode}`);
  }

  writePrivatePreference('zen_guard_app_limits', dailyEntries.length ? preferenceXml(dailyEntries) : emptyPreferenceXml());
  writePrivatePreference('zen_guard_intent_apps', intentEntries.length ? preferenceXml(intentEntries) : emptyPreferenceXml());
  writePrivatePreference('zen_guard_rolling_limits', rollingEntries.length ? preferenceXml(rollingEntries) : emptyPreferenceXml());

  const activeStore = mode === 'daily'
    ? privateFile('shared_prefs/zen_guard_app_limits.xml')
    : mode === 'timed-visit'
      ? privateFile('shared_prefs/zen_guard_intent_apps.xml')
      : privateFile('shared_prefs/zen_guard_rolling_limits.xml');
  assert.ok(activeStore.includes(settingsPackage), `${mode} store does not contain the literal stale Settings rule`);
  pass(`${mode} store contains a literal stale ${settingsPackage} rule`);
}

async function openAccessibilityList() {
  await startActionAndWait('android.settings.ACCESSIBILITY_SETTINGS');
  if (matchingNode('resource-id', 'com.android.settings:id/main_switch_bar')) {
    shell('input', 'keyevent', 'KEYCODE_BACK');
  }
  await waitForNode('text', 'Zen Mode protection');
}

async function openAccessibilityControl() {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    await startActionAndWait('android.settings.ACCESSIBILITY_SETTINGS');
    const ui = dumpUi();
    const switchBar = ui.nodes.find((node) =>
      node['resource-id'] === 'com.android.settings:id/main_switch_bar');
    if (switchBar) return;
    const serviceEntry = ui.nodes.find((node) => node.text === 'Zen Mode protection');
    if (serviceEntry) {
      tapBounds(serviceEntry.bounds);
      await waitForNode('resource-id', 'com.android.settings:id/main_switch_bar');
      return;
    }
    await sleep(500);
  }
  throw new Error(`Could not reach the Zen Mode accessibility control; focus=${currentFocus()}`);
}

async function enableServiceThroughUi() {
  await openAccessibilityControl();
  if (!serviceIsEnabled()) {
    const switchNode = await waitForNode('resource-id', 'android:id/switch_widget');
    assert.equal(switchNode.checked, 'false');
    await tapNode('resource-id', 'com.android.settings:id/main_switch_bar');
    await tapNode('text', 'Allow');
  }
  await waitFor('accessibility service enabled and bound', () => serviceIsEnabled() && serviceIsBound(), 10_000);
  pass('service enabled through Android confirmation UI and bound to the installed app');
}

async function disableServiceThroughUi({ requireEnabled = false } = {}) {
  await openAccessibilityControl();
  if (requireEnabled) {
    assert.equal(serviceIsEnabled(), true, 'Service was not enabled before the user-facing disable step');
  }
  if (serviceIsEnabled()) {
    const switchNode = await waitForNode('resource-id', 'android:id/switch_widget');
    assert.equal(switchNode.checked, 'true');
    await tapNode('resource-id', 'com.android.settings:id/main_switch_bar');
    const turnOff = await waitForNode('text', 'Turn off', { timeoutMs: 5_000 });
    tapBounds(turnOff.bounds);
  }
  await waitFor('accessibility service disabled', () => !serviceIsEnabled(), 10_000);
  pass('service disabled through Android Settings UI');
}

async function launchApp() {
  const url = `exp+zen-mode://expo-development-client/?url=${encodeURIComponent(metroUrl)}`;
  adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', url, '-n', `${appPackage}/.MainActivity`);
  await waitForFocus(appPackage);
  await waitFor('Zen Mode JavaScript UI', () => {
    const ui = dumpUi();
    return ui.nodes.some((node) => ['Feeds', 'App limits', 'Lock'].includes(node['content-desc']));
  }, 90_000, 500);
}

async function launchDevelopmentClient() {
  const url = `exp+zen-mode://expo-development-client/?url=${encodeURIComponent(metroUrl)}`;
  adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', url, '-n', `${appPackage}/.MainActivity`);
  await waitForFocus(appPackage, 60_000);

  await waitFor('development bundle or first-run dev menu', () => {
    const ui = dumpUi();
    return ui.nodes.find((node) =>
      node.text === 'Set up the guard' || node.text === 'Continue' || node['content-desc'] === 'Close');
  }, 90_000, 500);

  for (let attempt = 0; attempt < 8; attempt += 1) {
    const continueNode = matchingNode('text', 'Continue');
    if (!continueNode) break;
    tapBounds(continueNode.bounds);
    await sleep(3_000);
  }
  if (!currentFocus().includes(appPackage)) {
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', url, '-n', `${appPackage}/.MainActivity`);
    await waitForFocus(appPackage, 60_000);
  }
  const closeNode = matchingNode('text', 'Reload')
    ? await waitForNode('content-desc', 'Close', { timeoutMs: 2_000 }).catch(() => null)
    : null;
  if (closeNode) tapBounds(closeNode.bounds);
  await waitForNode('text', 'Set up the guard', { timeoutMs: 60_000 });
}

async function createConsentProtectionAndLockThroughUi() {
  await tapNode('content-desc', 'I understand, and I agree to this use.');
  const consent = await waitForNode('content-desc', 'I understand, and I agree to this use.');
  assert.equal(consent.checked, 'true');
  await tapNode('content-desc', 'Turn on the guard');
  await sleep(1_000);
  await enableServiceThroughUi();

  await launchApp();
  await tapNode('content-desc', 'Manage settings lock');
  await waitForNode('text', 'Settings unlocked');
  await tapNode('content-desc', 'Lock for 7 days');
  await waitForNode('text', 'Settings locked');

  const consentPolicy = readFileSync(resolve(root,
    'modules/zen-guard/android/src/main/java/com/maxmauroner/zenguard/ConsentPolicy.kt'), 'utf8');
  const currentVersion = /CURRENT_VERSION\s*=\s*(\d+)/.exec(consentPolicy)?.[1];
  assert.ok(currentVersion, 'Could not read native current consent version');
  const preferences = privateFile('shared_prefs/zen_guard_preferences.xml');
  assert.match(preferences, new RegExp(`<int name="consent_version" value="${currentVersion}"`));
  assert.match(preferences, /<boolean name="protection_enabled" value="true"/);
  pass(`current native consent v${currentVersion}, active protection, and live 7-day SecureStore lock created through UI`);
}

function assertLockStorageIntact(expectedSecureStoreHash) {
  assert.equal(privateSha256('shared_prefs/SecureStore.xml'), expectedSecureStoreHash,
    'SecureStore changed while exercising the native Settings escape path');
  const preferences = privateFile('shared_prefs/zen_guard_preferences.xml');
  assert.match(preferences, /<boolean name="protection_enabled" value="true"/);
  pass('SecureStore-backed lock data and protection preference remain unchanged');
}

async function assertLockStillActive(expectedSecureStoreHash) {
  await launchApp();
  await tapNode('content-desc', 'Lock');
  await waitForNode('text', 'Settings locked');
  assertLockStorageIntact(expectedSecureStoreHash);
  pass('in-app settings lock remains live in the real app UI');
}

async function assertHomeEnforcementResumes(mode) {
  startComponent(clockActivity);
  const focus = await waitForFocus(launcherPackage, 8_000);
  assert.ok(focus.includes(launcherPackage));
  await sleep(1_000);
  assert.ok(currentFocus().includes(launcherPackage), 'Home enforcement transition did not settle');
  pass(`${mode} enforcement resumed for ${clockPackage} and sent it Home`);
}

async function assertTimedOverlayAndSettingsEscape() {
  shell('input', 'keyevent', 'KEYCODE_HOME');
  await waitForFocus(launcherPackage);
  await waitFor('accessibility service rebound after UI inspection', () => serviceIsBound(), 10_000);
  await sleep(2_000);
  startComponent(clockActivity);
  const before = await waitFor('real timed-visit accessibility overlay above visible Clock', () => {
    const windows = shell('dumpsys', 'window', 'windows');
    const records = parseWindowRecords(windows);
    const overlay = records.find((record) => record.ownerPackage === appPackage &&
      record.type === 'ACCESSIBILITY_OVERLAY' && record.isOnScreen && record.isVisible);
    const clock = records.find((record) => record.ownerPackage === clockPackage &&
      record.isOnScreen && record.isVisible);
    return overlay && clock && overlay.index < clock.index ? { overlay, clock } : false;
  }, 10_000, 100);
  pass(`real timed-visit Clock overlay relation: ${visibleWindowEvidence(before.overlay)} above ${visibleWindowEvidence(before.clock)}`);

  let settingsBeneathOverlay;
  await assertSettingsStaysForeground(
    'android.settings.ACCESSIBILITY_SETTINGS',
    'Accessibility Settings opened beneath an existing Zen overlay',
    (windows) => {
      const records = parseWindowRecords(windows);
      const overlay = records.find((record) => record.ownerPackage === appPackage &&
        record.type === 'ACCESSIBILITY_OVERLAY' && record.isOnScreen && record.isVisible);
      const settings = records.find((record) => record.ownerPackage === settingsPackage &&
        record.isOnScreen && record.isVisible);
      if (!settingsBeneathOverlay && overlay && settings && overlay.index < settings.index) {
        settingsBeneathOverlay = { overlay, settings };
      }
    }
  );
  assert.ok(settingsBeneathOverlay,
    'Did not observe a visible Settings window record beneath the visible Zen-owned overlay');
  pass(`Settings-under-overlay relation: ${visibleWindowEvidence(settingsBeneathOverlay.overlay)} above ${visibleWindowEvidence(settingsBeneathOverlay.settings)}`);
  const afterRecords = parseWindowRecords(shell('dumpsys', 'window', 'windows'));
  assert.ok(!afterRecords.some((record) => record.ownerPackage === appPackage &&
    record.type === 'ACCESSIBILITY_OVERLAY' && record.isOnScreen && record.isVisible),
  'The timed overlay remained visible above Settings');
  pass('existing timed overlay was removed and did not run its Home callback over Settings');
}

async function selectRuleMode(label) {
  for (let attempt = 0; attempt < 3 && !matchingNode('text', label); attempt += 1) {
    shell('input', 'swipe', '540', '650', '540', '1900', '300');
    await sleep(300);
  }
  await tapNode('text', label);
  await waitFor(`selected ${label} rule mode`, () => {
    const { nodes } = dumpUi();
    return nodes.some((node) => node.selected === 'true' && node['content-desc']?.includes(label));
  });
}

async function saveSelectedClockRule() {
  for (let attempt = 0; attempt < 3 && !matchingNode('text', 'Save rule'); attempt += 1) {
    shell('input', 'swipe', '540', '1900', '540', '650', '300');
    await sleep(300);
  }
  await tapNode('text', 'Save rule');
  await waitForNode('content-desc', 'Edit Clock');
}

/** Reproduces the failed media sequence through the real JS-to-native save path. */
async function assertClockRuleMutationAndFirstLaunch() {
  step('Clock timed-visit rule mutation and first-launch enforcement');
  await launchApp();
  await tapNode('content-desc', 'App limits');
  await waitForNode('text', 'No app limits yet.');
  await tapNode('text', '＋ Add app');
  await tapNode('content-desc', 'Search apps');
  shell('input', 'text', 'Clock');
  await waitForNode('content-desc', 'Add Clock');
  shell('input', 'keyevent', 'KEYCODE_BACK');
  await sleep(300);
  await tapNode('content-desc', 'Add Clock');
  await waitForNode('text', 'Daily');

  await selectRuleMode('Visit');
  await tapNode('text', '1m');
  await saveSelectedClockRule();
  await tapNode('content-desc', 'Edit Clock');
  await selectRuleMode('Daily');
  await saveSelectedClockRule();
  await tapNode('content-desc', 'Edit Clock');
  await selectRuleMode('Visit');
  await saveSelectedClockRule();

  const intentPreferences = privateFile('shared_prefs/zen_guard_intent_apps.xml');
  const dailyPreferences = privateFile('shared_prefs/zen_guard_app_limits.xml');
  const rollingPreferences = privateFile('shared_prefs/zen_guard_rolling_limits.xml');
  assert.match(intentPreferences, /com\.google\.android\.deskclock/);
  assert.doesNotMatch(dailyPreferences, /com\.google\.android\.deskclock/);
  assert.doesNotMatch(rollingPreferences, /com\.google\.android\.deskclock/);
  pass('real UI visit→daily→visit save sequence leaves only the Clock timed-visit rule stored');

  shell('input', 'keyevent', 'KEYCODE_HOME');
  await waitForFocus(launcherPackage);
  await sleep(500);
  startComponent(clockActivity);
  const relation = await waitFor('Clock timed overlay on the first launch after the final save', () => {
    const records = parseWindowRecords(shell('dumpsys', 'window', 'windows'));
    const overlay = records.find((record) => record.ownerPackage === appPackage &&
      record.type === 'ACCESSIBILITY_OVERLAY' && record.isOnScreen && record.isVisible);
    const clock = records.find((record) => record.ownerPackage === clockPackage &&
      record.isOnScreen && record.isVisible);
    return overlay && clock && overlay.index < clock.index ? { overlay, clock } : false;
  }, 10_000, 50);
  pass(`first Clock launch enforced: ${visibleWindowEvidence(relation.overlay)} above ${visibleWindowEvidence(relation.clock)}`);
  shell('cmd', 'statusbar', 'expand-notifications');
  await sleep(1_000);
  shell('cmd', 'statusbar', 'collapse');
  await waitFor('Clock timed overlay after a transient SystemUI window', () => {
    const records = parseWindowRecords(shell('dumpsys', 'window', 'windows'));
    const overlay = records.find((record) => record.ownerPackage === appPackage &&
      record.type === 'ACCESSIBILITY_OVERLAY' && record.isOnScreen && record.isVisible);
    const clock = records.find((record) => record.ownerPackage === clockPackage &&
      record.isOnScreen && record.isVisible);
    return overlay && clock && overlay.index < clock.index;
  }, 5_000, 50);
  pass('Clock timed overlay survived a transient SystemUI notification window');
  await assertSettingsStaysForeground(
    'android.settings.ACCESSIBILITY_SETTINGS',
    'Clock first-launch overlay: Accessibility Settings',
  );
  const afterRecords = parseWindowRecords(shell('dumpsys', 'window', 'windows'));
  assert.ok(!afterRecords.some((record) => record.ownerPackage === appPackage &&
    record.type === 'ACCESSIBILITY_OVERLAY' && record.isOnScreen && record.isVisible),
  'The first-launch Clock overlay remained visible above Settings');
  pass('Clock first-launch overlay was removed by the Settings safety boundary');
}

async function runScenario(mode, day, secureStoreHash) {
  step(`${mode} stale Settings rule`);
  shell('am', 'force-stop', appPackage);
  seedScenario(mode, day, Date.now());
  await enableServiceThroughUi();

  await assertSettingsStaysForeground('android.settings.SETTINGS', `${mode}: Android Settings`);
  await assertSettingsStaysForeground('android.settings.ACCESSIBILITY_SETTINGS', `${mode}: Accessibility Settings`);

  if (mode === 'timed-visit') {
    await assertTimedOverlayAndSettingsEscape();
  } else {
    await assertHomeEnforcementResumes(mode);
  }

  assertLockStorageIntact(secureStoreHash);
  await disableServiceThroughUi({ requireEnabled: true });
}

let destructiveRunStarted = false;

async function main() {
  step('Isolated device and installed-build identity');
  assert.ok(serial, 'Set ZEN_GUARD_TEST_SERIAL explicitly; no device is selected by default');
  assert.equal(
    dataResetApproval,
    `clear-${appPackage}-on-${taskAvdName}`,
    `Set ZEN_GUARD_ALLOW_TASK_DATA_RESET=clear-${appPackage}-on-${taskAvdName} to acknowledge task-app data reset`,
  );
  assert.match(serial, /^emulator-/, 'Refusing to clear or seed data on a non-emulator serial');
  assert.equal(shell('getprop', 'ro.kernel.qemu').trim(), '1', 'Target is not an Android emulator');
  assert.equal(shell('getprop', 'ro.boot.qemu.avd_name').trim(), taskAvdName,
    `Target is not the dedicated ${taskAvdName} task AVD`);
  assert.equal(shell('getprop', 'ro.build.version.sdk').trim(), '35', 'This harness is pinned to the Android 15/API 35 task AVD');
  assert.doesNotThrow(() => shell('run-as', appPackage, 'id'), 'Installed app is not a debuggable test build');
  const sourceHead = run('git', ['rev-parse', 'HEAD']).trim();
  assert.match(sourceHead, /^[0-9a-f]{40}$/);
  const productionSourceChanges = run('git', [
    'status', '--porcelain', '--', 'app.json', 'src', 'modules/zen-guard/android/src/main',
  ]).trim();
  assert.equal(productionSourceChanges, '',
    `Production source differs from ${sourceHead}: ${productionSourceChanges}`);
  const localApkSha256 = run('sha256sum', [debugApkPath]).trim().split(/\s+/)[0];
  const installedApkPath = shell('pm', 'path', appPackage).trim().replace(/^package:/, '');
  const installedApkSha256 = shell('sha256sum', installedApkPath).trim().split(/\s+/)[0];
  assert.equal(installedApkSha256, localApkSha256, 'Installed APK differs from the locally built debug APK');
  const packageDump = shell('dumpsys', 'package', appPackage);
  assert.match(packageDump, /versionName=1\.0\.3/);
  assert.match(packageDump, /versionCode=5\b/);
  assert.match(packageDump, /targetSdk=36\b/);
  assert.ok(packageDump.includes(serviceComponent.split('/')[1]));
  assert.ok(shell('pm', 'path', clockPackage).trim(), `${clockPackage} is unavailable`);
  pass(`${serial}: Android 15/API 35, ${appPackage} 1.0.3 (5), target SDK 36, debuggable task build`);
  pass(`source HEAD=${sourceHead}; APK sha256=${localApkSha256}; ${/lastUpdateTime=([^\n]+)/.exec(packageDump)?.[0]}`);
  adb('logcat', '-c');
  destructiveRunStarted = true;

  step('Real consent, protection, service confirmation, and settings lock setup');
  const pendingTurnOff = matchingNode('text', 'Turn off');
  if (pendingTurnOff) {
    tapBounds(pendingTurnOff.bounds);
    await waitFor('prior accessibility confirmation to finish', () => !serviceIsEnabled(), 10_000);
  }
  if (serviceIsEnabled()) await disableServiceThroughUi();
  shell('pm', 'clear', appPackage);
  await launchDevelopmentClient();
  await createConsentProtectionAndLockThroughUi();
  const secureStoreHash = privateSha256('shared_prefs/SecureStore.xml');
  await assertClockRuleMutationAndFirstLaunch();
  assertLockStorageIntact(secureStoreHash);
  await disableServiceThroughUi({ requireEnabled: true });

  const deviceDay = shell('date', '+%Y%j').trim();
  assert.match(deviceDay, /^\d{7}$/);
  const day = Number(deviceDay.slice(0, 4)) * 1000 + Number(deviceDay.slice(4));

  for (const mode of ['daily', 'timed-visit', 'rolling']) {
    await runScenario(mode, day, secureStoreHash);
  }

  step('Final lock and user escape assertions');
  await assertLockStillActive(secureStoreHash);
  assert.equal(serviceIsEnabled(), false, 'Service should finish disabled by the user-facing path');
  const crashLog = adb('logcat', '-b', 'crash', '-d');
  assert.ok(!crashLog.includes(appPackage), `The task app crashed during the device run:\n${crashLog}`);
  pass('no task-app entry was recorded in Android\'s crash buffer during the run');
  pass('all three stale-rule scenarios passed; service finishes disabled and the lock remains active');
  assert.match(shell('pm', 'clear', appPackage), /Success/, 'Final task-app data cleanup failed');
  destructiveRunStarted = false;
  pass('task-app test data was cleared after the final assertions');
}

async function cleanupAfterFailure() {
  if (!destructiveRunStarted) return;
  try {
    if (serviceIsEnabled()) await disableServiceThroughUi();
  } catch (cleanupError) {
    process.stderr.write(`WARN Could not disable the service during cleanup: ${cleanupError.message}\n`);
  }
  try {
    shell('pm', 'clear', appPackage);
  } catch (cleanupError) {
    process.stderr.write(`WARN Could not clear task-app data during cleanup: ${cleanupError.message}\n`);
  }
}

main().then(() => {
  writeEvidence();
}).catch(async (error) => {
  await cleanupAfterFailure();
  const failure = `\nFAIL ${error.stack ?? error}\n`;
  evidenceTranscript += failure;
  writeEvidence();
  process.stderr.write(failure);
  process.exitCode = 1;
});
