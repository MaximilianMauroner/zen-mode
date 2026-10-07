#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { appendFileSync, chmodSync, closeSync, copyFileSync, mkdirSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stampRelease, releaseIdentity } from './stamp-nightly-version.mjs';
import { verifyReleaseArtifacts } from './verify-release-artifacts.mjs';
import { uploadPlayInternal } from './upload-play-internal.mjs';
import { preflightRelease, releaseFailure, validateEasVersion, checkReleaseResources, localChildEnvironment } from './release-environment.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const app = 'zen-mode';
const command = process.argv[2] ?? 'run';
let cancelled = false;
let buildChild;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  cancelled = true;
  buildChild?.kill(signal);
});

function run(program, args, capture = false) {
  const result = spawnSync(program, args, { cwd: root, encoding: 'utf8',
    stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit', maxBuffer: 16 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${program} failed; inspect private runner logs`);
  return result.stdout?.trim();
}

function ledger(action, args = []) {
  if (cancelled) throw new Error('Release cancelled; reconcile the ledger');
  return JSON.parse(run('python3', [join(root, 'scripts/nightly-ledger.py'), app, action, ...args], true));
}

function hosted(job) {
  if (process.env.GITHUB_ACTIONS !== 'true' || process.env.GITHUB_JOB !== job ||
      process.env.GITHUB_WORKFLOW !== 'Android nightly Internal release' ||
      process.env.GITHUB_REPOSITORY !== 'MaximilianMauroner/zen-mode' || process.env.GITHUB_REF !== 'refs/heads/main') {
    throw new Error('Release phases require their isolated hosted nightly job');
  }
}

function output(name, value) {
  if (!process.env.GITHUB_OUTPUT) throw new Error('GITHUB_OUTPUT is required');
  appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`);
}

function checkedSha() {
  const sha = process.env.CHECKED_SHA;
  if (!/^[a-f0-9]{40}$/.test(sha ?? '') || run('git', ['rev-parse', 'HEAD'], true) !== sha) {
    throw new Error('Expected checkout of the exact checked SHA');
  }
  return sha;
}

function reservation() {
  const record = JSON.parse(readFileSync(join(process.env.RUNNER_TEMP, 'reservation', 'reservation.json'), 'utf8'));
  if (record.build !== true || record.sha !== checkedSha() || !/^[a-zA-Z0-9-]+$/.test(record.id ?? '') ||
      record.runId !== process.env.GITHUB_RUN_ID || record.runAttempt !== process.env.GITHUB_RUN_ATTEMPT) {
    throw new Error('Expected the confirmed reservation for the checked SHA');
  }
  releaseIdentity(record.version, record.versionCode);
  return record;
}

function evidence(stage, sha, status, record, failure) {
  const path = resolve(process.env.RELEASE_ARTIFACTS_DIR, `${stage}-${sha.slice(0, 12)}`);
  mkdirSync(path, { recursive: true });
  writeFileSync(join(path, 'release.json'), `${JSON.stringify({ app, sha, ...record, status,
    ...(failure ? { failure: releaseFailure(stage) } : {}), finishedAt: new Date().toISOString() }, null, 2)}\n`);
}

function profiles() {
  const config = JSON.parse(readFileSync(join(root, 'eas.json'), 'utf8'));
  if (typeof config.cli?.version !== 'string') throw new Error('Expected fetched eas.json cli.version to specify a minimum');
  for (const name of ['nightly', 'nightly-apk']) {
    if (config.build?.[name]?.autoIncrement !== false) throw new Error(`${name} must explicitly disable autoIncrement`);
  }
  return config;
}

async function checks() {
  hosted('checks');
  if (process.env.EXPO_TOKEN || process.env.PLAY_SERVICE_ACCOUNT_JSON || process.env.PLAY_SERVICE_ACCOUNT_KEY_PATH || process.env.GH_TOKEN || process.env.GITHUB_TOKEN) {
    throw new Error('Source checks must not receive release credentials');
  }
  const sha = run('git', ['rev-parse', 'HEAD'], true);
  try {
    profiles();
    Object.assign(process.env, preflightRelease(root));
    run('npm', ['ci']);
    run('npm', ['test']);
    run('npm', ['run', 'test:nightly']);
    run('npm', ['run', 'lint']);
    run('npx', ['tsc', '--noEmit']);
    run('npx', ['expo', 'export', '--platform', 'web', '--max-workers', '2']);
    preflightRelease(root);
    if (cancelled) throw new Error('Checks cancelled');
    evidence('checks', sha, 'succeeded');
    output('eas-version', validateEasVersion(run(process.env.EAS_BIN ?? 'eas', ['--version'], true), profiles().cli.version));
    output('sha', sha);
  } catch (error) {
    evidence('checks', sha, cancelled ? 'cancelled' : 'failed', undefined, true);
    throw error;
  }
}

function validateSecrets() {
  hosted('reserve');
  try {
    const key = JSON.parse(process.env.PLAY_SERVICE_ACCOUNT_JSON ?? '');
    if (!process.env.EXPO_TOKEN?.trim() || key?.type !== 'service_account' ||
        typeof key.client_email !== 'string' || !key.client_email || typeof key.private_key !== 'string' || !key.private_key) throw new Error();
  } catch {
    if (/^[a-f0-9]{40}$/.test(process.env.CHECKED_SHA ?? '')) evidence('preflight', process.env.CHECKED_SHA, 'failed', undefined, true);
    throw new Error('EXPO_TOKEN and a full PLAY_SERVICE_ACCOUNT_JSON are required');
  }
  // Only readiness crosses steps. The key never becomes a file on this runner.
  output('ready', 'true');
}

function reserve() {
  hosted('reserve');
  const sha = checkedSha();
  try {
    if (process.env.RELEASE_SECRETS_READY !== 'true') throw new Error('Release secrets must be validated before reservation');
    validateEasVersion(`eas-cli/${process.env.CHECKED_EAS_VERSION}`, profiles().cli.version);
    checkReleaseResources(root);
    run('git', ['fetch', 'origin', 'main']);
    if (run('git', ['rev-parse', 'origin/main'], true) !== sha) throw new Error('Main moved after checks; no identity reserved');
    const record = ledger('reserve', [sha]);
    if (record.build) {
      evidence('reserve', sha, 'running', record);
      const path = join(process.env.RUNNER_TEMP, 'reservation');
      mkdirSync(path, { recursive: true });
      // Only confirmed, nonsecret identity fields cross the fresh-runner boundary.
      writeFileSync(join(path, 'reservation.json'), `${JSON.stringify({ build: true, id: record.id, sha,
        version: record.version, versionCode: record.versionCode,
        runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT })}\n`);
    }
    output('build', record.build === true ? 'true' : 'false');
  } catch (error) {
    evidence('preflight', sha, 'failed', undefined, true);
    throw error;
  }
}

async function runEas(args, logPath) {
  const log = openSync(logPath, 'w', 0o600);
  const monitor = setInterval(() => {
    const memory = spawnSync('free', ['-m'], { encoding: 'utf8', env: localChildEnvironment(process.env, 'checks') });
    if (memory.status === 0) console.log(memory.stdout.trim());
  }, 60000);
  try {
    if (cancelled) throw new Error('Build cancelled');
    const child = spawn(process.env.EAS_BIN ?? 'eas', args, { cwd: root, stdio: ['ignore', log, log] });
    buildChild = child;
    await new Promise((accept, reject) => {
      child.once('error', reject);
      child.once('close', (code) => code === 0 ? accept() : reject(new Error('EAS build failed; inspect private runner logs')));
    });
  } finally {
    buildChild = undefined;
    clearInterval(monitor);
    closeSync(log);
  }
}

function artifacts() {
  const path = resolve(process.env.RELEASE_ARTIFACTS_DIR, 'verified');
  return { path, apk: join(path, `${app}.apk`), aab: join(path, `${app}.aab`) };
}

function verify(record, apk, aab) {
  verifyReleaseArtifacts(apk, aab, { package: 'com.lab4code.zenmode', version: record.version, versionCode: record.versionCode }, app);
}

async function build() {
  hosted('build');
  let record;
  let stage = 'preflight';
  const logs = join(homedir(), '.local/state/lab4code-releases/logs', app);
  try {
    record = reservation();
    if (!process.env.EXPO_TOKEN?.trim() || process.env.GITHUB_TOKEN || process.env.GH_TOKEN || process.env.PLAY_SERVICE_ACCOUNT_JSON || process.env.PLAY_SERVICE_ACCOUNT_KEY_PATH) {
      throw new Error('Build requires Expo credentials only');
    }
    const config = profiles();
    Object.assign(process.env, preflightRelease(root));
    stampRelease(root, record.version, record.versionCode);
    config.cli = { ...config.cli, appVersionSource: 'local' };
    writeFileSync(join(root, 'eas.json'), `${JSON.stringify(config, null, 2)}\n`);
    mkdirSync(logs, { recursive: true, mode: 0o700 });
    chmodSync(logs, 0o700);
    mkdirSync(process.env.RELEASE_ARTIFACTS_DIR, { recursive: true });
    const apk = join(process.env.RELEASE_ARTIFACTS_DIR, `${app}.apk`);
    const aab = join(process.env.RELEASE_ARTIFACTS_DIR, `${app}.aab`);
    for (const [profile, target] of [['nightly-apk', apk], ['nightly', aab]]) {
      stage = profile === 'nightly-apk' ? 'apk-build' : 'aab-build';
      preflightRelease(root);
      await runEas(['build', '--platform', 'android', '--profile', profile, '--local', '--non-interactive', '--freeze-credentials', '--output', target], join(logs, `${stage}.log`));
    }
    stage = 'artifact-verify';
    verify(record, apk, aab);
    const targets = artifacts();
    mkdirSync(targets.path, { recursive: true });
    copyFileSync(apk, targets.apk);
    copyFileSync(aab, targets.aab);
    if (cancelled) throw new Error('Build cancelled');
    evidence(stage, record.sha, 'built', record);
    output('outcome', 'built');
  } catch (error) {
    evidence(stage, process.env.CHECKED_SHA, cancelled ? 'cancelled' : 'failed', record, true);
    if (!cancelled) output('outcome', 'failed');
    throw error;
  } finally {
    rmSync(logs, { recursive: true, force: true });
  }
}

function verifyUpload() {
  hosted('upload');
  let record;
  try {
    record = reservation();
    const { apk, aab } = artifacts();
    verify(record, apk, aab);
  } catch (error) {
    evidence('artifact-verify', process.env.CHECKED_SHA, 'failed', record, true);
    if (!cancelled) output('outcome', 'failed');
    throw error;
  }
}

async function upload() {
  hosted('upload');
  const record = reservation();
  const keyPath = join(process.env.RUNNER_TEMP, 'play-service-account.json');
  try {
    if (process.env.EXPO_TOKEN || process.env.GITHUB_TOKEN || process.env.GH_TOKEN) throw new Error('Upload requires Play credentials only');
    const key = JSON.parse(process.env.PLAY_SERVICE_ACCOUNT_JSON ?? '');
    writeFileSync(keyPath, JSON.stringify(key), { mode: 0o600, flag: 'wx' });
    if (cancelled) throw new Error('Upload cancelled');
    await uploadPlayInternal({ app, aabPath: artifacts().aab, version: record.version, versionCode: record.versionCode, keyPath });
    if (cancelled) throw new Error('Upload cancelled');
    evidence('play-upload', record.sha, 'succeeded', record);
    output('outcome', 'succeeded');
  } catch (error) {
    // A transport error can occur after Play committed. Never finalize uncertain uploads.
    evidence('play-upload', record.sha, cancelled ? 'cancelled' : 'uncertain', record, true);
    throw new Error(releaseFailure('play-upload').message);
  } finally {
    rmSync(keyPath, { force: true });
  }
}

function finishHosted() {
  hosted('finish');
  const record = reservation();
  const status = process.env.RELEASE_OUTCOME;
  if (!['failed', 'succeeded'].includes(status) || cancelled) throw new Error('Outcome is uncertain; reconcile the active reservation');
  ledger('finish', [record.id, status]);
}

try {
  if (command === 'run') run('python3', [join(root, 'scripts/nightly-host-lock.py'), process.execPath, join(root, 'scripts/nightly-local-release.mjs'), 'run']);
  else if (command === 'checks') await checks();
  else if (command === 'validate-secrets') validateSecrets();
  else if (command === 'reserve') reserve();
  else if (command === 'build') await build();
  else if (command === 'verify-upload') verifyUpload();
  else if (command === 'upload') await upload();
  else if (command === 'finish-hosted') finishHosted();
  else if (['status', 'finish'].includes(command)) console.log(JSON.stringify(ledger(command, process.argv.slice(3)), null, 2));
  else throw new Error('Expected run, status, finish, or a hosted release phase');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
