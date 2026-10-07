import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { releaseFailure, localChildEnvironment, validateEasVersion } from '../scripts/release-environment.mjs';

const sha = 'a'.repeat(40);
const record = { build: true, id: 'fixture', sha, version: '0.1.6', versionCode: 9 };
const config = { cli: { version: '>= 20.5.1' }, build: { nightly: { autoIncrement: false }, 'nightly-apk': { autoIncrement: false } } };

// All external effects are mocked; these tests never access real credentials or state.
async function phase(command, options = {}) {
  const job = { 'validate-secrets': 'reserve', 'verify-upload': 'upload', 'finish-hosted': 'finish' }[command] ?? command;
  const env = { GITHUB_ACTIONS: 'true', GITHUB_JOB: job, GITHUB_WORKFLOW: 'Android nightly Internal release',
    GITHUB_REPOSITORY: 'MaximilianMauroner/zen-mode', GITHUB_REF: 'refs/heads/main', CHECKED_SHA: sha,
    CHECKED_EAS_VERSION: '20.5.1', RELEASE_SECRETS_READY: 'true', RELEASE_RESERVATION: JSON.stringify(record),
    RELEASE_ARTIFACTS_DIR: '/fixture/evidence', RUNNER_TEMP: '/fixture', GITHUB_OUTPUT: '/fixture/outputs', ...options.env };
  if (command === 'build' && !Object.hasOwn(options.env ?? {}, 'EXPO_TOKEN')) env.EXPO_TOKEN = 'fixture-expo';
  const filename = options.local ? 'nightly-local-release.mjs' : 'nightly-release.mjs';
  if (options.local) Object.assign(env, { GITHUB_ACTIONS: 'false', NIGHTLY_HOST_LOCKED: '1', EXPO_TOKEN: 'fixture-expo', GITHUB_TOKEN: 'fixture-github', PLAY_SERVICE_ACCOUNT_KEY_PATH: '/fixture/key' }, options.env);
  const url = new URL(`../scripts/${filename}`, import.meta.url);
  const source = readFileSync(url, 'utf8').replace(/^#!.*\n/, '').replace(/^import .*;\n/gm, '').replaceAll('import.meta.url', JSON.stringify(url.href));
  const commands = [], writes = [], outputs = {}, errors = [], removed = [], signals = {};
  let preflights = 0;
  let uploads = 0;
  await runInNewContext(`(async () => { ${source} })()`, {
    process: { argv: ['node', filename, command], env, platform: 'linux', execPath: 'node', on: (signal, handler) => { signals[signal] = handler; } },
    console: { log() {}, error: (error) => errors.push(error) },
    spawnSync(program, args, opts) {
      commands.push({ program, args: Array.from(args), env: opts.env ?? { ...env } });
      if (program === 'npm' && ((options.failChecks && args[0] === 'test') || (options.failInstall && args[0] === 'ci'))) return { status: 1, stderr: 'private failing detail' };
      if (program === 'python3' && args[0].endsWith('nightly-ledger.py')) {
        if (options.failReserve && args[2] === 'reserve' || options.failFinish && args[2] === 'finish') return { status: 1 };
        return { status: 0, stdout: JSON.stringify(options.skipRelease ? { build: false } : record) };
      }
      if (program === 'git' && args[0] === 'show') return { status: 0, stdout: JSON.stringify(config) };
      if (program === 'git' && args[0] === 'rev-parse') return { status: 0, stdout: args[1] === 'origin/main' && options.mainMoved ? 'b'.repeat(40) : sha };
      if (args[0] === '--version') return { status: 0, stdout: 'eas-cli/20.5.1 test' };
      return { status: 0, stdout: '' };
    },
    spawn(program, args, opts) {
      commands.push({ program, args: Array.from(args), env: opts.env ?? { ...env } });
      const child = new EventEmitter();
      child.kill = () => {};
      queueMicrotask(() => { if (options.cancelBuild) signals.SIGTERM(); child.emit('close', options.failBuild || options.cancelBuild ? 1 : 0); });
      return child;
    },
    readFileSync(path) {
      if (path.endsWith('/key')) return JSON.stringify({ type: 'service_account', client_email: 'fixture', private_key: 'fixture' });
      if (path.endsWith('eas.json')) return JSON.stringify(config);
      if (path.endsWith('package.json')) return JSON.stringify({ name: 'zen-mode' });
      if (path.endsWith('app.json')) return JSON.stringify({ expo: { android: { package: 'com.lab4code.zenmode' } } });
      return '';
    },
    writeFileSync(path, data, opts) {
      if (path.endsWith('release.json')) {
        writes.push({ path, record: JSON.parse(data) });
        if (options.failEvidence && JSON.parse(data).finishedAt) throw new Error('ENOSPC');
      }
      if (path.endsWith('play-service-account.json')) writes.push({ path, opts: JSON.parse(JSON.stringify(opts)) });
    },
    appendFileSync(path, data) { const [name, ...value] = data.trim().split('='); outputs[name] = value.join('='); },
    existsSync: () => false, copyFileSync() {}, chmodSync() {}, closeSync() {}, openSync: () => 1, mkdirSync() {},
    mkdtempSync: () => '/fixture/worktree', rmSync: (path) => removed.push(path),
    URL, homedir, tmpdir, isAbsolute, join, resolve, fileURLToPath,
    stampRelease() {}, releaseIdentity() {},
    verifyReleaseArtifacts() { if (options.failVerify) throw new Error('private verification detail'); },
    uploadPlayInternal: async () => { uploads++; if (options.failUpload) throw new Error('private unknown Play result'); },
    preflightRelease() { if (++preflights === 2 && options.failSecondPreflight) throw new Error('resource failure'); return env; },
    checkReleaseResources() {}, releaseFailure, localChildEnvironment, validateEasVersion,
    setInterval: () => 1, clearInterval() {},
  });
  return { commands, writes, outputs, errors, removed, uploads };
}
const ledgerCommands = (result) => result.commands.filter(({ program, args }) => program === 'python3' && args[0].endsWith('nightly-ledger.py'));

test('hosted phase commands reject local execution', async () => {
  const result = await phase('checks', { env: { GITHUB_ACTIONS: 'false' } });
  assert.match(result.errors[0], /isolated hosted/);
  assert.equal(result.commands.length, 0);
});

for (const failure of ['failInstall', 'failChecks', 'failSecondPreflight']) test(`${failure} retains sanitized evidence without a reservation`, async () => {
  const result = await phase('checks', { [failure]: true });
  assert.equal(ledgerCommands(result).length, 0);
  assert.equal(result.outputs.sha, undefined);
  const { finishedAt, ...evidence } = result.writes.at(-1).record;
  assert.deepEqual(evidence, { app: 'zen-mode', sha, status: 'failed', failure: releaseFailure('checks') });
  assert.equal(new Date(finishedAt).toISOString(), finishedAt);
  assert.ok(result.commands.every(({ env }) => !env.EXPO_TOKEN && !env.GITHUB_TOKEN && !env.PLAY_SERVICE_ACCOUNT_JSON));
});

test('checks outputs exact checked SHA and stable CLI proof only after checks', async () => {
  const result = await phase('checks');
  assert.equal(result.outputs.sha, sha);
  assert.equal(result.outputs['eas-version'], '20.5.1');
  assert.equal(result.errors.length, 0);
});

test('moving main and missing hosted secrets cannot reserve', async () => {
  for (const options of [{ mainMoved: true }, { env: { RELEASE_SECRETS_READY: '' } }, { env: { CHECKED_EAS_VERSION: '16.28.0' } }]) {
    const result = await phase('reserve', options);
    assert.equal(ledgerCommands(result).length, 0);
    assert.equal(result.outputs.reservation, undefined);
    assert.equal(result.writes.at(-1).record.failure.stage, 'preflight');
  }
  const secrets = await phase('validate-secrets');
  assert.match(secrets.errors[0], /required/);
  assert.equal(secrets.outputs.ready, undefined);
  assert.equal(secrets.writes.some(({ path }) => path.endsWith('play-service-account.json')), false);
});

test('secret readiness validates both secrets without files or children', async () => {
  const result = await phase('validate-secrets', { env: { EXPO_TOKEN: 'fixture', PLAY_SERVICE_ACCOUNT_JSON: JSON.stringify({ type: 'service_account', client_email: 'fixture', private_key: 'fixture' }) } });
  assert.equal(result.outputs.ready, 'true');
  assert.equal(result.commands.length, 0);
  assert.equal(result.writes.length, 0);
});

test('reserve persists before exposing identity; lost reserve response exposes none', async () => {
  const passed = await phase('reserve', { env: { GITHUB_TOKEN: 'fixture' } });
  assert.equal(JSON.parse(passed.outputs.reservation).id, record.id);
  assert.equal(ledgerCommands(passed)[0].args[2], 'reserve');
  assert.ok(passed.commands.every(({ program }) => !['npm', 'npx', 'eas'].includes(program)));
  const lost = await phase('reserve', { failReserve: true });
  assert.equal(lost.outputs.reservation, undefined);
  assert.equal(lost.outputs.build, undefined);
  assert.equal(lost.writes.at(-1).record.id, undefined);
  assert.equal(ledgerCommands(lost).length, 1);
  const skipped = await phase('reserve', { skipRelease: true });
  assert.equal(skipped.outputs.build, 'false');
  assert.equal(skipped.writes.length, 0);
});

test('build only invokes EAS with Expo, never checks, ledger or Play', async () => {
  const result = await phase('build');
  assert.equal(result.outputs.outcome, 'built');
  assert.equal(result.errors.length, 0);
  const children = result.commands.filter(({ program }) => program === 'eas');
  assert.equal(children.length, 2);
  for (const child of children) {
    assert.equal(child.env.EXPO_TOKEN, 'fixture-expo');
    assert.equal(child.env.GITHUB_TOKEN, undefined);
    assert.equal(child.env.PLAY_SERVICE_ACCOUNT_JSON, undefined);
    assert.ok(child.args.includes('--freeze-credentials'));
  }
  assert.ok(result.commands.every(({ program }) => !['npm', 'npx', 'python3'].includes(program)));
  assert.equal(result.uploads, 0);
});

test('cancellation or lost build evidence exposes no terminal output and never finishes', async () => {
  for (const options of [{ cancelBuild: true }, { failEvidence: true }]) {
    const result = await phase('build', options);
    assert.equal(result.outputs.outcome, undefined);
    assert.equal(ledgerCommands(result).length, 0);
    assert.ok(result.removed.some((path) => path.endsWith('logs/zen-mode')));
  }
});

test('fresh upload verification stops unapproved artifacts before Play access', async () => {
  const result = await phase('verify-upload', { failVerify: true });
  assert.equal(result.outputs.outcome, 'failed');
  assert.equal(result.uploads, 0);
  assert.equal(result.writes.at(-1).record.failure.stage, 'artifact-verify');
});

test('upload creates a private ephemeral key and removes it; unknown result cannot finalize', async () => {
  const key = JSON.stringify({ type: 'service_account', client_email: 'fixture', private_key: 'fixture' });
  const passed = await phase('upload', { env: { PLAY_SERVICE_ACCOUNT_JSON: key } });
  assert.equal(passed.outputs.outcome, 'succeeded');
  assert.deepEqual(passed.writes.find(({ opts }) => opts)?.opts, { mode: 0o600, flag: 'wx' });
  assert.ok(passed.removed.includes('/fixture/play-service-account.json'));
  assert.equal(ledgerCommands(passed).length, 0);
  const lost = await phase('upload', { failUpload: true, env: { PLAY_SERVICE_ACCOUNT_JSON: key } });
  assert.equal(lost.outputs.outcome, undefined);
  assert.equal(lost.writes.at(-1).record.status, 'uncertain');
  assert.ok(lost.removed.includes('/fixture/play-service-account.json'));
});

test('finish rejects ambiguous outcome and does not retry lost completion', async () => {
  const uncertain = await phase('finish-hosted');
  assert.equal(ledgerCommands(uncertain).length, 0);
  const complete = await phase('finish-hosted', { env: { RELEASE_OUTCOME: 'succeeded', GITHUB_TOKEN: 'fixture' } });
  assert.deepEqual(ledgerCommands(complete)[0].args.slice(2), ['finish', record.id, 'succeeded']);
  const lost = await phase('finish-hosted', { failFinish: true, env: { RELEASE_OUTCOME: 'succeeded' } });
  assert.equal(ledgerCommands(lost).length, 1);
  assert.equal(lost.errors.length, 1);
});

test('local runner retains manual builds and narrows child authentication', async () => {
  const result = await phase('run', { local: true });
  assert.equal(result.errors.length, 0);
  const checks = result.commands.filter(({ program }) => ['npm', 'npx'].includes(program));
  assert.ok(checks.length > 0);
  for (const child of checks) assert.ok(!child.env.EXPO_TOKEN && !child.env.GITHUB_TOKEN && !child.env.PLAY_SERVICE_ACCOUNT_KEY_PATH);
  for (const child of result.commands.filter(({ program }) => program === 'eas')) {
    assert.equal(child.env.EXPO_TOKEN, 'fixture-expo');
    assert.equal(child.env.GITHUB_TOKEN, undefined);
    assert.equal(child.env.PLAY_SERVICE_ACCOUNT_KEY_PATH, undefined);
  }
  const uploader = result.commands.find(({ program, args }) => program === 'node' && args[0].endsWith('upload-play-internal.mjs'));
  assert.equal(uploader.env.PLAY_SERVICE_ACCOUNT_KEY_PATH, '/fixture/key');
  assert.equal(uploader.env.EXPO_TOKEN, undefined);
  assert.equal(uploader.env.GITHUB_TOKEN, undefined);
  assert.equal(ledgerCommands(result).at(-1).args[2], 'finish');
});

test('local ENOSPC still finalizes confirmed reservation and cleans the worktree', async () => {
  const result = await phase('run', { local: true, failEvidence: true });
  assert.equal(ledgerCommands(result).at(-1).args[2], 'finish');
  assert.ok(result.removed.includes('/fixture/worktree'));
  assert.match(result.errors[0], /ENOSPC/);
});

test('local missing Play key fails before reservation and existing EAS login remains supported', async () => {
  const missing = await phase('run', { local: true, env: { PLAY_SERVICE_ACCOUNT_KEY_PATH: '' } });
  assert.equal(ledgerCommands(missing).length, 0);
  assert.equal(missing.writes.at(-1).record.failure.stage, 'preflight');
  const login = await phase('run', { local: true, env: { EXPO_TOKEN: '' } });
  assert.equal(login.errors.length, 0);
  assert.ok(login.commands.some(({ program }) => program === 'eas'));
  assert.ok(login.commands.filter(({ program }) => program === 'eas').every(({ env }) => env.EXPO_TOKEN === undefined));
});
