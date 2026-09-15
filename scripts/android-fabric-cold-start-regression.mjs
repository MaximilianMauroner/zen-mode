#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';

const androidHome = process.env.ANDROID_HOME ?? '/home/codex/android-sdk';
const adbBinary = process.env.ADB ?? resolve(androidHome, 'platform-tools/adb');
const serial = process.env.ZEN_FABRIC_TEST_SERIAL;
const variant = process.env.ZEN_FABRIC_VARIANT ?? 'debug';
const validTarget = Number(process.env.ZEN_FABRIC_VALID_STARTS ?? '100');
const maxAttempts = Number(process.env.ZEN_FABRIC_MAX_ATTEMPTS ?? String(validTarget + 30));
const observeMs = Number(process.env.ZEN_FABRIC_OBSERVE_MS ?? '15000');
const metroUrl = process.env.ZEN_FABRIC_METRO_URL ?? 'http://10.0.2.2:8081';
const appPackage = 'com.lab4code.zenmode';
const component = `${appPackage}/.MainActivity`;

assert.ok(serial, 'Set ZEN_FABRIC_TEST_SERIAL to the disposable moodqa emulator serial');
assert.ok(['debug', 'release'].includes(variant), 'ZEN_FABRIC_VARIANT must be debug or release');
assert.ok(Number.isInteger(validTarget) && validTarget > 0, 'valid-start target must be positive');
assert.ok(Number.isInteger(maxAttempts) && maxAttempts >= validTarget, 'max attempts must cover target');
assert.ok(Number.isFinite(observeMs) && observeMs >= 1000, 'observation must be at least one second');

const sleep = (milliseconds) => new Promise((resolveSleep) => setTimeout(resolveSleep, milliseconds));
const stamp = () => new Date().toISOString();

function run(args, { timeout = 45_000, allowFailure = false } = {}) {
  const result = spawnSync(adbBinary, ['-s', serial, ...args], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    timeout,
  });
  if (!allowFailure && (result.error || result.status !== 0)) {
    throw new Error([
      `adb ${args.join(' ')} failed`,
      result.error?.message,
      result.stdout,
      result.stderr,
    ].filter(Boolean).join('\n'));
  }
  return result;
}

function adb(...args) {
  return run(args).stdout.trim();
}

function adbMaybe(...args) {
  return run(args, { allowFailure: true });
}

function mostRecentExit() {
  const output = adb('shell', 'dumpsys', 'activity', 'exit-info', appPackage);
  return output.split(/(?=ApplicationExitInfo #0:)/)[1]?.split(/(?=ApplicationExitInfo #1:)/)[0]?.trim() ?? 'none';
}

function selectedLogs() {
  const crash = adbMaybe('logcat', '-b', 'crash', '-d', '-v', 'threadtime').stdout.trim();
  const main = adbMaybe(
    'logcat', '-b', 'main', '-d', '-v', 'threadtime',
    'libc:F', 'AndroidRuntime:E', 'ReactNative:W', 'unknown:ReactNative:W', '*:S',
  ).stdout.trim();
  return [crash, main].filter(Boolean).join('\n');
}

const avdName = adb('emu', 'avd', 'name').split(/\r?\n/)[0];
assert.equal(avdName, 'moodqa', `Refusing to run against non-task AVD ${JSON.stringify(avdName)}`);
assert.match(adb('shell', 'pm', 'path', appPackage), /^package:/, `${appPackage} is not installed`);

const launchArgs = variant === 'debug'
  ? [
      'shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
      '-d', `exp+zen-mode://expo-development-client/?url=${encodeURIComponent(metroUrl)}`,
      '-n', component,
    ]
  : ['shell', 'am', 'start', '-W', '-n', component];

let valid = 0;
let invalid = 0;
let crashes = 0;
let attempt = 0;

process.stdout.write([
  `${stamp()} HARNESS_START`,
  `SERIAL=${serial}`,
  `AVD=${avdName}`,
  `FINGERPRINT=${adb('shell', 'getprop', 'ro.build.fingerprint')}`,
  `ABI=${adb('shell', 'getprop', 'ro.product.cpu.abi')}`,
  `VARIANT=${variant}`,
  `VALID_TARGET=${validTarget}`,
  `MAX_ATTEMPTS=${maxAttempts}`,
  `OBSERVE_MS=${observeMs}`,
].join('\n') + '\n');

while (valid < validTarget && attempt < maxAttempts) {
  attempt += 1;
  process.stdout.write(`${stamp()} ATTEMPT=${attempt} VALID_BEFORE=${valid} START\n`);
  adbMaybe('logcat', '-b', 'all', '-c');
  adbMaybe('shell', 'am', 'force-stop', appPackage);

  const launch = run(launchArgs, { allowFailure: true });
  process.stdout.write(`LAUNCH_STATUS=${launch.status ?? 'null'}${launch.error ? ` ERROR=${launch.error.message}` : ''}\n`);
  if (launch.stdout.trim()) process.stdout.write(`${launch.stdout.trim()}\n`);
  if (launch.stderr.trim()) process.stdout.write(`LAUNCH_STDERR=${launch.stderr.trim()}\n`);

  await sleep(observeMs);
  const pid = adbMaybe('shell', 'pidof', appPackage).stdout.trim();
  const logs = selectedLogs();
  const launchSucceeded = launch.status === 0 && !launch.error;
  const coldLaunch = /(?:^|\n)LaunchState: COLD(?:\n|$)/.test(launch.stdout);
  const crashEvidence = logs.includes(appPackage) &&
    /(Fatal signal|signal 11 \(SIGSEGV\)|MountingCoordinator::pullTransaction)/.test(logs);

  if (logs) process.stdout.write(`SELECTED_LOGS_BEGIN\n${logs}\nSELECTED_LOGS_END\n`);

  if (crashEvidence) {
    crashes += 1;
    process.stdout.write(`${stamp()} ATTEMPT=${attempt} RESULT=CRASH PID=${pid || 'NONE'}\n${mostRecentExit()}\n`);
  } else if (!launchSucceeded) {
    invalid += 1;
    process.stdout.write(`${stamp()} ATTEMPT=${attempt} RESULT=INVALID_LAUNCH_FAILURE PID=${pid || 'NONE'}\n${mostRecentExit()}\n`);
  } else if (!coldLaunch) {
    invalid += 1;
    process.stdout.write(`${stamp()} ATTEMPT=${attempt} RESULT=INVALID_NOT_COLD PID=${pid || 'NONE'}\n${mostRecentExit()}\n`);
  } else if (!pid) {
    invalid += 1;
    process.stdout.write(`${stamp()} ATTEMPT=${attempt} RESULT=INVALID_NO_PROCESS\n${mostRecentExit()}\n`);
  } else {
    valid += 1;
    process.stdout.write(`${stamp()} ATTEMPT=${attempt} RESULT=VALID PID=${pid} VALID_AFTER=${valid}\n`);
  }
}

process.stdout.write(
  `${stamp()} HARNESS_END VARIANT=${variant} ATTEMPTS=${attempt} VALID=${valid} INVALID=${invalid} CRASHES=${crashes}\n`,
);

assert.equal(crashes, 0, `${crashes} native crash(es) observed`);
assert.equal(valid, validTarget, `Reached only ${valid}/${validTarget} valid starts in ${attempt} attempts`);
