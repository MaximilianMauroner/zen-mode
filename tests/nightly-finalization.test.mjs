import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';

// Execute the runner with all external effects mocked. No release, credentials, or ledger is used.
const source = readFileSync(new URL('../scripts/nightly-release.mjs', import.meta.url), 'utf8')
  .replace(/^#!.*\n/, '')
  .replace(/^import .*;\n/gm, '')
  .replaceAll('import.meta.url', JSON.stringify(new URL('../scripts/nightly-release.mjs', import.meta.url).href));

async function runRelease({ failBuild = false, failFinish = false } = {}) {
  const finishes = [];
  const removed = [];
  const errors = [];
  const writes = [];
  const config = { cli: { version: '>= 20.5.1' }, build: { nightly: { autoIncrement: false }, 'nightly-apk': { autoIncrement: false } } };
  const reservation = { build: true, id: 'fixture', version: '0.1.6', versionCode: 9 };
  const environment = { NIGHTLY_HOST_LOCKED: '1', PLAY_SERVICE_ACCOUNT_KEY_PATH: '/fixture/key' };
  await runInNewContext(`(async () => { ${source} })()`, {
    process: { argv: ['node', 'nightly-release.mjs', 'run'], env: environment, platform: 'linux', execPath: 'node' },
    console: { log() {}, error: (message) => errors.push(message) },
    spawnSync(program, args) {
      if (program === 'ssh') {
        if (args.at(-1).includes("'finish'")) {
          finishes.push(args.at(-1));
          if (failFinish) return { status: 1, stderr: 'fixture ledger unavailable' };
          return { status: 0, stdout: '{}' };
        }
        return { status: 0, stdout: JSON.stringify(reservation) };
      }
      if (program === 'git' && args[0] === 'show') return { status: 0, stdout: JSON.stringify(config) };
      if (program === 'git' && args[0] === 'rev-parse') return { status: 0, stdout: 'a'.repeat(40) };
      return { status: 0, stdout: '' };
    },
    spawn() {
      const child = new EventEmitter();
      queueMicrotask(() => child.emit('close', failBuild ? 1 : 0));
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
        if (record.finishedAt) throw new Error('ENOSPC: fixture evidence disk full');
      }
    },
    existsSync: () => false,
    chmodSync() {}, closeSync() {}, openSync: () => 1, mkdirSync() {},
    mkdtempSync: () => '/fixture/temporary', rmSync: (path) => removed.push(path),
    URL, homedir, tmpdir, join, resolve, fileURLToPath,
    stampRelease() {}, verifyReleaseArtifacts() {}, uploadPlayInternal: async () => {},
    preflightRelease: () => environment,
    releaseFailure: (stage) => ({ stage, message: 'fixture failure' }),
    setInterval: () => 1, clearInterval() {},
  });
  return { finishes, removed, errors, writes };
}

for (const failBuild of [false, true]) {
  test(`evidence disk failure finalizes a ${failBuild ? 'failed' : 'successful'} release reservation`, async () => {
    const result = await runRelease({ failBuild });
    assert.equal(result.finishes.length, 1);
    assert.ok(result.finishes[0].endsWith(failBuild ? "'failed'" : "'succeeded'"));
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
