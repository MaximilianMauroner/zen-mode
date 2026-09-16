#!/usr/bin/env node
import { spawn, spawnSync } from 'node:child_process';
import { copyFileSync, chmodSync, closeSync, existsSync, openSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { stampRelease } from './stamp-nightly-version.mjs';
import { verifyReleaseArtifacts } from './verify-release-artifacts.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const app = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).name;
const coordinator = process.env.RELEASE_COORDINATOR ?? 'coding';
const eas = process.env.EAS_BIN ?? 'eas';
const command = process.argv[2] ?? 'run';
const quote = (value) => "'" + value.replaceAll("'", "'\\''") + "'";

function run(program, args, cwd = root, capture = false, input) {
  const result = spawnSync(program, args, { cwd, encoding: 'utf8', input,
    stdio: capture ? ['pipe', 'pipe', 'pipe'] : 'inherit', maxBuffer: 16 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${program} failed (${result.status}): ${capture ? result.stderr : 'see log'}`);
  return result.stdout?.trim();
}

async function runEas(args, cwd, logPath) {
  // EAS can include a credential-bearing encoded job in failure output.
  // Keep its complete output in private files, outside terminal and shared artifacts.
  const log = openSync(logPath, 'w', 0o600);
  const child = spawn(eas, args, { cwd, stdio: ['ignore', log, log] });
  const monitor = setInterval(() => {
    const result = spawnSync(process.platform === 'darwin' ? 'memory_pressure' : 'free',
      process.platform === 'darwin' ? ['-Q'] : ['-m'], { encoding: 'utf8' });
    if (result.status === 0) console.log(result.stdout.trim());
  }, 60000);
  try {
    await new Promise((accept, reject) => {
      child.once('error', reject);
      child.once('close', (code) => code === 0 ? accept() : reject(new Error(`EAS ${args[0]} failed (${code}); private log: ${logPath}`)));
    });
  } finally {
    clearInterval(monitor);
    closeSync(log);
  }
}

function ledger(action, args = []) {
  if (coordinator === 'local' && process.platform === 'darwin') throw new Error('The Mac must use the shared coding release ledger');
  const source = readFileSync(join(root, 'scripts/nightly-ledger.py'), 'utf8');
  const parameters = [app, action, ...args].map(quote).join(' ');
  const response = coordinator === 'local'
    ? run('python3', ['-', app, action, ...args], root, true, source)
    : run('ssh', ['-o', 'BatchMode=yes', coordinator, `python3 - ${parameters}`], root, true, source);
  return JSON.parse(response);
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
  const submit = config.submit?.nightly?.android;
  if (submit?.track !== 'internal' || submit.releaseStatus !== 'completed') {
    throw new Error('nightly submit must release to Internal');
  }
}

async function buildRelease() {
  run('git', ['fetch', 'origin', 'main']);
  const sha = run('git', ['rev-parse', 'origin/main'], root, true);
  const reservation = ledger('reserve', [sha]);
  console.log(JSON.stringify({ app, ...reservation }));
  if (!reservation.build) return;
  let temporary;
  let sourceRoot;
  let worktreeAdded = false;
  let output;
  let outcome = 'failed';
  try {
    const credentialPath = process.env.PLAY_SERVICE_ACCOUNT_KEY_PATH;
    if (credentialPath && !existsSync(credentialPath)) throw new Error('PLAY_SERVICE_ACCOUNT_KEY_PATH does not exist');
    output = resolve(process.env.RELEASE_ARTIFACTS_DIR ?? join(homedir(), 'Downloads/lab4code-releases'), app,
      `${reservation.version}-${reservation.versionCode}-${sha.slice(0, 12)}`);
    mkdirSync(output, { recursive: true });
    writeFileSync(join(output, 'release.json'), `${JSON.stringify(reservation, null, 2)}\n`);
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
    }
    stampRelease(sourceRoot, reservation.version, reservation.versionCode);
    const easPath = join(sourceRoot, 'eas.json');
    const config = JSON.parse(readFileSync(easPath, 'utf8'));
    config.cli = { ...config.cli, appVersionSource: 'local' };
    if (credentialPath) {
      const key = join(sourceRoot, 'google-service-account.json');
      copyFileSync(credentialPath, key);
      chmodSync(key, 0o600);
      config.submit.nightly.android.serviceAccountKeyPath = './google-service-account.json';
    } else {
      // EAS Submit uses the existing managed Play key when no local path is supplied.
      delete config.submit.nightly.android.serviceAccountKeyPath;
    }
    writeFileSync(easPath, `${JSON.stringify(config, null, 2)}\n`);
    assertProfiles(sourceRoot);
    const logs = join(homedir(), '.local/state/lab4code-releases/logs', app, reservation.id);
    mkdirSync(logs, { recursive: true, mode: 0o700 });
    chmodSync(logs, 0o700);
    const apk = join(output, `${app}-${reservation.version}.apk`);
    const aab = join(output, `${app}-${reservation.version}.aab`);
    await runEas(['build', '--platform', 'android', '--profile', 'nightly-apk', '--local', '--non-interactive', '--freeze-credentials', '--output', apk], sourceRoot, join(logs, 'apk-build.log'));
    checkResources();
    await runEas(['build', '--platform', 'android', '--profile', 'nightly', '--local', '--non-interactive', '--freeze-credentials', '--output', aab], sourceRoot, join(logs, 'aab-build.log'));
    const stamped = JSON.parse(readFileSync(join(sourceRoot, 'app.json'), 'utf8')).expo;
    verifyReleaseArtifacts(apk, aab, { package: stamped.android.package, version: reservation.version, versionCode: reservation.versionCode }, app);
    await runEas(['submit', '--platform', 'android', '--profile', 'nightly', '--path', aab, '--non-interactive', '--wait'], sourceRoot, join(logs, 'submit.log'));
    outcome = 'succeeded';
    console.log(`Internal release ready: ${output}`);
  } finally {
    // Record the attempt even when dependency installation, checks, build, or upload fails.
    // If SSH itself fails here, the active reservation safely blocks another upload.
    try {
      ledger('finish', [reservation.id, outcome]);
      if (output) writeFileSync(join(output, 'release.json'), `${JSON.stringify({ ...reservation, status: outcome, finishedAt: new Date().toISOString() }, null, 2)}\n`);
    } finally {
      if (worktreeAdded) run('git', ['worktree', 'remove', '--force', sourceRoot]);
      if (temporary) rmSync(temporary, { recursive: true, force: true });
    }
  }
}

try {
  if (command === 'run' && process.env.NIGHTLY_HOST_LOCKED !== '1') {
    const locked = spawnSync('python3', [join(root, 'scripts/nightly-host-lock.py'), process.execPath, fileURLToPath(import.meta.url), 'run'], { stdio: 'inherit' });
    if (locked.error) throw locked.error;
    process.exitCode = locked.status ?? 1;
  } else if (command === 'run') await buildRelease();
  else if (['status', 'seed', 'finish'].includes(command)) console.log(JSON.stringify(ledger(command, process.argv.slice(3)), null, 2));
  else throw new Error('Usage: node scripts/nightly-release.mjs [run|status|seed VERSION CODE SHA|finish ID failed|succeeded]');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
