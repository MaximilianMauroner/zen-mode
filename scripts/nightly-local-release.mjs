#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { chmodSync, closeSync, copyFileSync, existsSync, openSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stampRelease } from './stamp-nightly-version.mjs';
import { verifyReleaseArtifacts } from './verify-release-artifacts.mjs';
import { localChildEnvironment } from './release-environment.mjs';
import { preflightRelease, releaseFailure } from './release-environment.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const app = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).name;
const eas = process.env.EAS_BIN ?? 'eas';
const command = process.argv[2] ?? 'run';
let cancelled = false;
let buildChild;
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    cancelled = true;
    buildChild?.kill(signal);
  });
}
function assertNotCancelled() {
  if (cancelled) throw new Error('Release cancelled; reconcile the active reservation before another run');
}

function run(program, args, cwd = root, capture = false, input, auth = 'checks') {
  const result = spawnSync(program, args, { cwd, encoding: 'utf8', input, env: localChildEnvironment(process.env, auth),
    stdio: capture ? ['pipe', 'pipe', 'pipe'] : 'inherit', maxBuffer: 16 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${program} failed (${result.status}): ${capture ? result.stderr : 'see log'}`);
  return result.stdout?.trim();
}

async function runEas(args, cwd, logPath) {
  // EAS can include a credential-bearing encoded job in failure output.
  // Keep its complete output in private files, outside terminal and shared artifacts.
  const log = openSync(logPath, 'w', 0o600);
  assertNotCancelled();
  const child = spawn(eas, args, { cwd, env: localChildEnvironment(process.env, 'eas'), stdio: ['ignore', log, log] });
  buildChild = child;
  const monitor = setInterval(() => {
    const result = spawnSync(process.platform === 'darwin' ? 'memory_pressure' : 'free',
      process.platform === 'darwin' ? ['-Q'] : ['-m'], { encoding: 'utf8', env: localChildEnvironment(process.env, 'checks') });
    if (result.status === 0) console.log(result.stdout.trim());
  }, 60000);
  try {
    await new Promise((accept, reject) => {
      child.once('error', reject);
      child.once('close', (code) => code === 0 ? accept() : reject(new Error(`EAS ${args[0]} failed (${code}); private log: ${logPath}`)));
    });
  } finally {
    buildChild = undefined;
    clearInterval(monitor);
    closeSync(log);
  }
}

function ledger(action, args = []) {
  assertNotCancelled();
  return JSON.parse(run('python3', [join(root, 'scripts/nightly-ledger.py'), app, action, ...args], root, true, undefined, 'ledger'));
}

function checkResources() {
  run('df', ['-h', root]);
  if (process.platform === 'darwin') {
    run('vm_stat', []);
    const total = Number(run('sysctl', ['-n', 'hw.memsize'], root, true));
    if (total < 8 * 1024 ** 3) throw new Error('Local builds require at least 8 GiB RAM');
  } else {
    run('free', ['-h']);
  }
}

function assertProfiles(sourceRoot) {
  const config = JSON.parse(readFileSync(join(sourceRoot, 'eas.json'), 'utf8'));
  for (const name of ['nightly', 'nightly-apk']) {
    if (!config.build?.[name] || config.build[name].autoIncrement !== false) {
      throw new Error(`${name} must explicitly disable autoIncrement`);
    }
  }

}

async function buildRelease() {
  run('git', ['fetch', 'origin', 'main']);
  const sha = run('git', ['rev-parse', 'origin/main'], root, true);
  try {
    const config = JSON.parse(run('git', ['show', `${sha}:eas.json`], root, true));
    if (typeof config.cli?.version !== 'string') {
      throw new Error('Expected fetched eas.json cli.version to specify a minimum');
    }
    const keyPath = process.env.PLAY_SERVICE_ACCOUNT_KEY_PATH;
    if (!keyPath || !isAbsolute(keyPath)) throw new Error('PLAY_SERVICE_ACCOUNT_KEY_PATH must be an absolute path');
    try {
      const key = JSON.parse(readFileSync(keyPath, 'utf8'));
      if (key?.type !== 'service_account' || typeof key.client_email !== 'string' || !key.client_email || typeof key.private_key !== 'string' || !key.private_key) throw new Error();
    } catch {
      throw new Error('Expected a readable full Play service-account JSON key');
    }
    Object.assign(process.env, preflightRelease(root, localChildEnvironment(process.env, 'checks'), config.cli.version));
  } catch (error) {
    const output = resolve(process.env.RELEASE_ARTIFACTS_DIR ?? join(homedir(), 'Downloads/lab4code-releases'), app, `preflight-${sha.slice(0, 12)}`);
    mkdirSync(output, { recursive: true });
    writeFileSync(join(output, 'release.json'), `${JSON.stringify({ app, sha, status: 'failed', failure: releaseFailure('preflight'), finishedAt: new Date().toISOString() }, null, 2)}\n`);
    throw error;
  }
  let reservation;
  let temporary;
  let sourceRoot;
  let worktreeAdded = false;
  let output;
  let outcome = 'failed';
  let stage = 'checks';
  let failure;
  try {
    temporary = mkdtempSync(join(tmpdir(), `${app}-nightly-`));
    sourceRoot = join(temporary, 'source');
    run('git', ['worktree', 'add', '--detach', sourceRoot, sha]);
    worktreeAdded = true;
    checkResources();
    if (existsSync(join(sourceRoot, 'bun.lock'))) {
      run('bun', ['install', '--frozen-lockfile'], sourceRoot);
      run('bun', ['run', 'test:run'], sourceRoot);
      run('bun', ['run', 'test:nightly'], sourceRoot);
      run('bun', ['run', 'lint'], sourceRoot);
      run('bun', ['run', 'typecheck'], sourceRoot);
      run('bun', ['run', 'verify:android-release-config'], sourceRoot);
    } else {
      run('npm', ['ci'], sourceRoot);
      run('npm', ['test'], sourceRoot);
      run('npm', ['run', 'test:nightly'], sourceRoot);
      run('npm', ['run', 'lint'], sourceRoot);
      run('npx', ['tsc', '--noEmit'], sourceRoot);
      run('npx', ['expo', 'export', '--platform', 'web', '--max-workers', '2'], sourceRoot);
    }
    assertProfiles(sourceRoot);
    preflightRelease(sourceRoot, localChildEnvironment(process.env, 'checks'));
    assertNotCancelled();
    reservation = ledger('reserve', [sha]);
    console.log(JSON.stringify({ app, ...reservation }));
    if (!reservation.build) return;
    output = resolve(process.env.RELEASE_ARTIFACTS_DIR ?? join(homedir(), 'Downloads/lab4code-releases'), app,
      `${reservation.version}-${reservation.versionCode}-${sha.slice(0, 12)}`);
    mkdirSync(output, { recursive: true });
    writeFileSync(join(output, 'release.json'), `${JSON.stringify(reservation, null, 2)}\n`);
    stampRelease(sourceRoot, reservation.version, reservation.versionCode);
    const easPath = join(sourceRoot, 'eas.json');
    const config = JSON.parse(readFileSync(easPath, 'utf8'));
    config.cli = { ...config.cli, appVersionSource: 'local' };
    writeFileSync(easPath, `${JSON.stringify(config, null, 2)}\n`);
    const logs = join(homedir(), '.local/state/lab4code-releases/logs', app, reservation.id);
    mkdirSync(logs, { recursive: true, mode: 0o700 });
    chmodSync(logs, 0o700);
    const apk = join(output, `${app}-${reservation.version}.apk`);
    const aab = join(output, `${app}-${reservation.version}.aab`);
    stage = 'apk-build';
    await runEas(['build', '--platform', 'android', '--profile', 'nightly-apk', '--local', '--non-interactive', '--freeze-credentials', '--output', apk], sourceRoot, join(logs, 'apk-build.log'));
    stage = 'aab-build';
    checkResources();
    await runEas(['build', '--platform', 'android', '--profile', 'nightly', '--local', '--non-interactive', '--freeze-credentials', '--output', aab], sourceRoot, join(logs, 'aab-build.log'));
    stage = 'artifact-verify';
    const stamped = JSON.parse(readFileSync(join(sourceRoot, 'app.json'), 'utf8')).expo;
    verifyReleaseArtifacts(apk, aab, { package: stamped.android.package, version: reservation.version, versionCode: reservation.versionCode }, app, localChildEnvironment(process.env, 'checks'));
    const verified = join(output, 'verified');
    mkdirSync(verified);
    for (const artifact of [apk, aab]) copyFileSync(artifact, join(verified, artifact === apk ? `${app}.apk` : `${app}.aab`));
    assertNotCancelled();
    stage = 'play-upload';
    run(process.execPath, [join(root, 'scripts/upload-play-internal.mjs'), aab, reservation.version, String(reservation.versionCode)], root, false, undefined, 'upload');
    outcome = 'succeeded';
    console.log(`Internal release ready: ${output}`);
  } catch (error) {
    failure = releaseFailure(stage);
    if (!output) {
      output = resolve(process.env.RELEASE_ARTIFACTS_DIR ?? join(homedir(), 'Downloads/lab4code-releases'), app, `checks-${sha.slice(0, 12)}`);
      mkdirSync(output, { recursive: true });
    }
    throw error;
  } finally {
    // Cancellation or an uncertain state write leaves the reservation active for reconciliation.
    try {
      try {
        if (output) writeFileSync(join(output, 'release.json'), `${JSON.stringify({ app, sha, ...(reservation?.build ? reservation : {}), status: cancelled ? 'cancelled' : outcome, ...(failure ? { failure } : {}), finishedAt: new Date().toISOString() }, null, 2)}\n`);
      } finally {
        // Local evidence is optional; its failure must not leave the shared reservation active.
        if (reservation?.build && !cancelled) ledger('finish', [reservation.id, outcome]);
      }
    } finally {
      if (worktreeAdded) run('git', ['worktree', 'remove', '--force', sourceRoot]);
      if (temporary) rmSync(temporary, { recursive: true, force: true });
    }
  }
}

try {
  if (command === 'run' && process.env.NIGHTLY_HOST_LOCKED === '1') await buildRelease();
  else if (['status', 'finish'].includes(command)) console.log(JSON.stringify(ledger(command, process.argv.slice(3)), null, 2));
  else throw new Error('Usage: node scripts/nightly-release.mjs [run|status|finish ID failed|succeeded]');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
