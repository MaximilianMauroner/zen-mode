import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { releaseFailure } from '../scripts/release-environment.mjs';

// Execute the runner with all external effects mocked. No release, credentials, or ledger is used.
const source = readFileSync(new URL('../scripts/nightly-release.mjs', import.meta.url), 'utf8')
  .replace(/^#!.*\n/, '')
  .replace(/^import .*;\n/gm, '')
  .replaceAll('import.meta.url', JSON.stringify(new URL('../scripts/nightly-release.mjs', import.meta.url).href));

async function runRelease({ failBuild = false, failFinish = false, failReserve = false, failChecks = false, failInstall = false, failSecondPreflight = false, failEvidence = false, skipRelease = false, cancelBuild = false } = {}) {
  const finishes = [];
  const removed = [];
  const errors = [];
  const writes = [];
  const evidencePaths = [];
  const commands = [];
  const signals = {};
  const config = { cli: { version: '>= 20.5.1' }, build: { nightly: { autoIncrement: false }, 'nightly-apk': { autoIncrement: false } } };
  const reservation = skipRelease ? { build: false, reason: 'duplicate' } : { build: true, id: 'fixture', version: '0.1.6', versionCode: 9 };
  const environment = { NIGHTLY_HOST_LOCKED: '1', PLAY_SERVICE_ACCOUNT_KEY_PATH: '/fixture/key' };
  let preflights = 0;
  await runInNewContext(`(async () => { ${source} })()`, {
    process: { argv: ['node', 'nightly-release.mjs', 'run'], env: environment, platform: 'linux', execPath: 'node', on: (signal, handler) => { signals[signal] = handler; } },
    console: { log() {}, error: (message) => errors.push(message) },
    spawnSync(program, args) {
      commands.push([program, ...args]);
      if (program === 'npm' && args[0] === 'test' && failChecks) return { status: 1, stderr: 'fixture checks failed' };
      if (program === 'npm' && args[0] === 'ci' && failInstall) return { status: 1, stderr: 'fixture dependency failure with private detail' };
      if (program === 'python3') {
        if (args[2] === 'finish') {
          finishes.push(args.at(-1));
          if (failFinish) return { status: 1, stderr: 'fixture ledger unavailable' };
          return { status: 0, stdout: '{}' };
        }
        if (failReserve) return { status: 1, stderr: 'fixture reservation uncertain' };
        return { status: 0, stdout: JSON.stringify(reservation) };
      }
      if (program === 'git' && args[0] === 'show') return { status: 0, stdout: JSON.stringify(config) };
      if (program === 'git' && args[0] === 'rev-parse') return { status: 0, stdout: 'a'.repeat(40) };
      return { status: 0, stdout: '' };
    },
    spawn() {
      const child = new EventEmitter();
      child.kill = () => {};
      queueMicrotask(() => {
        if (cancelBuild) signals.SIGTERM();
        child.emit('close', failBuild || cancelBuild ? 1 : 0);
      });
      return child;
    },
    readFileSync(path) {
      if (path.endsWith('package.json')) return JSON.stringify({ name: 'zen-mode' });
      if (path.endsWith('eas.json')) return JSON.stringify(config);
      if (path.endsWith('app.json')) return JSON.stringify({ expo: { android: { package: 'fixture' } } });
      return '';
    },
    writeFileSync(path, data) {
      if (path.endsWith('release.json')) {
        const record = JSON.parse(data);
        writes.push(record);
        evidencePaths.push(path);
        if (record.finishedAt && failEvidence) throw new Error('ENOSPC: fixture evidence disk full');
      }
    },
    existsSync: () => false,
    copyFileSync() {}, chmodSync() {}, closeSync() {}, openSync: () => 1, mkdirSync() {},
    mkdtempSync: () => '/fixture/temporary', rmSync: (path) => removed.push(path),
    URL, homedir, tmpdir, join, resolve, fileURLToPath,
    stampRelease() {}, verifyReleaseArtifacts() {}, uploadPlayInternal: async () => {},
    preflightRelease: () => {
      if (++preflights === 2 && failSecondPreflight) throw new Error('fixture disk space unavailable');
      return environment;
    },
    releaseFailure,
    setInterval: () => 1, clearInterval() {},
  });
  return { finishes, removed, errors, writes, commands, evidencePaths };
}

for (const failBuild of [false, true]) {
  test(`evidence disk failure finalizes a ${failBuild ? 'failed' : 'successful'} release reservation`, async () => {
    const result = await runRelease({ failBuild, failEvidence: true });
    assert.equal(result.finishes.length, 1);
    assert.ok(result.finishes[0] === (failBuild ? 'failed' : 'succeeded'));
    assert.equal(result.writes.at(-1).status, failBuild ? 'failed' : 'succeeded');
    assert.deepEqual(result.removed, ['/fixture/temporary']);
    assert.match(result.errors[0], /ENOSPC/);
  });
}

test('ledger failure still cleans up the temporary checkout and reports the failure', async () => {
  const result = await runRelease({ failFinish: true });
  assert.equal(result.finishes.length, 1);
  assert.deepEqual(result.removed, ['/fixture/temporary']);
  assert.match(result.errors[0], /fixture ledger unavailable/);
});

test('fetched-source checks finish before reservation and a failed check does not reserve', async () => {
  const passed = await runRelease();
  const reserve = passed.commands.findIndex(([program, , , action]) => program === 'python3' && action === 'reserve');
  const tests = passed.commands.findIndex(([program, action]) => program === 'npm' && action === 'test');
  assert.ok(tests >= 0 && reserve > tests);
  const failed = await runRelease({ failChecks: true });
  assert.equal(failed.commands.some(([program]) => program === 'python3'), false);
  assert.equal(failed.finishes.length, 0);
});

test('uncertain reservation persistence never starts an EAS build or finalizes a reservation', async () => {
  const result = await runRelease({ failReserve: true });
  assert.equal(result.finishes.length, 0);
  assert.equal(result.writes.length, 1);
  assert.equal(result.writes[0].id, undefined);
  assert.equal(result.writes[0].versionCode, undefined);
  assert.match(result.errors[0], /reservation uncertain/);
});

for (const failure of ['failInstall', 'failChecks', 'failSecondPreflight']) {
  test(`${failure} retains sanitized source-check evidence without a reservation`, async () => {
    const result = await runRelease({ [failure]: true });
    assert.equal(result.commands.some(([program]) => program === 'python3'), false);
    assert.equal(result.finishes.length, 0);
    assert.equal(result.writes.length, 1);
    const { finishedAt, ...record } = result.writes[0];
    assert.deepEqual(record, { app: 'zen-mode', sha: 'a'.repeat(40), status: 'failed', failure: releaseFailure('checks') });
    assert.equal(new Date(finishedAt).toISOString(), finishedAt);
    assert.equal(result.evidencePaths[0], join(homedir(), 'Downloads/lab4code-releases/zen-mode/checks-aaaaaaaaaaaa/release.json'));
    assert.deepEqual(result.removed, ['/fixture/temporary']);
    assert.ok(result.commands.some(([program, action, operation]) => program === 'git' && action === 'worktree' && operation === 'remove'));
  });
}

test('duplicate source skips without recording a failed attempt', async () => {
  const result = await runRelease({ skipRelease: true });
  assert.equal(result.writes.length, 0);
  assert.equal(result.finishes.length, 0);
  assert.equal(result.errors.length, 0);
  assert.deepEqual(result.removed, ['/fixture/temporary']);
});

test('source-check evidence disk failure still cleans up without ledger access', async () => {
  const result = await runRelease({ failChecks: true, failEvidence: true });
  assert.equal(result.commands.some(([program]) => program === 'python3'), false);
  assert.deepEqual(result.removed, ['/fixture/temporary']);
  assert.match(result.errors[0], /ENOSPC/);
});

test('cancellation leaves the durable reservation active for manual reconciliation', async () => {
  const result = await runRelease({ cancelBuild: true });
  assert.equal(result.finishes.length, 0);
  assert.equal(result.writes.at(-1).status, 'cancelled');
  assert.deepEqual(result.removed, ['/fixture/temporary']);
});
