import { spawnSync } from 'node:child_process';
import { accessSync, constants, readFileSync, readdirSync, statSync, statfsSync } from 'node:fs';
import { totalmem } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

function readablePath(value, name, directory = false) {
  if (!value || !isAbsolute(value)) throw new Error(`${name} must be an absolute path`);
  try {
    accessSync(value, constants.R_OK);
    const stat = statSync(value);
    if (directory ? !stat.isDirectory() : !stat.isFile() || stat.size === 0) throw new Error();
  } catch {
    throw new Error(`${name} is missing or unreadable`);
  }
  return resolve(value);
}

export function releaseEnvironment(env = process.env) {
  if (!env.PATH?.trim()) throw new Error('PATH is required');
  const sdk = readablePath(env.ANDROID_HOME || env.ANDROID_SDK_ROOT, 'Android SDK', true);
  if (env.ANDROID_HOME && env.ANDROID_SDK_ROOT && resolve(env.ANDROID_HOME) !== resolve(env.ANDROID_SDK_ROOT)) {
    throw new Error('ANDROID_HOME and ANDROID_SDK_ROOT must match');
  }
  const bundletool = readablePath(env.ANDROID_BUNDLETOOL_JAR, 'ANDROID_BUNDLETOOL_JAR');
  const keyPath = readablePath(env.PLAY_SERVICE_ACCOUNT_KEY_PATH, 'PLAY_SERVICE_ACCOUNT_KEY_PATH');
  try {
    const key = JSON.parse(readFileSync(keyPath, 'utf8'));
    if (key.type !== 'service_account' || !key.client_email || !key.private_key) throw new Error();
  } catch {
    throw new Error('Expected a Play service-account JSON key');
  }
  return { ...env, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk,
    ANDROID_BUNDLETOOL_JAR: bundletool, PLAY_SERVICE_ACCOUNT_KEY_PATH: keyPath,
    EAS_BIN: env.EAS_BIN || 'eas' };
}

export function preflightRelease(root, env = process.env, cliVersion) {
  const environment = releaseEnvironment(env);
  const versions = readdirSync(join(environment.ANDROID_HOME, 'build-tools'), { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && /^\d/.test(entry.name))
    .map((entry) => entry.name).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  if (!versions.length) throw new Error('Android SDK build-tools are missing');
  for (const tool of ['aapt', 'apksigner']) {
    try { accessSync(join(environment.ANDROID_HOME, 'build-tools', versions[0], tool), constants.X_OK); }
    catch { throw new Error(`Android SDK ${tool} is unavailable`); }
  }
  const eas = spawnSync(environment.EAS_BIN, ['--version'], {
    cwd: root, env: environment, encoding: 'utf8', timeout: 30000,
  });
  if (eas.error || eas.status !== 0) throw new Error('EAS is unavailable');
  const constraint = cliVersion ?? JSON.parse(readFileSync(join(root, 'eas.json'), 'utf8')).cli.version;
  // Both release configurations require a minimum stable CLI version.
  const minimum = /^>=\s*(\d+)\.(\d+)\.(\d+)$/.exec(constraint);
  if (!minimum) throw new Error('Expected eas.json cli.version to specify >= MAJOR.MINOR.PATCH');
  const installed = /eas-cli\/(\d+)\.(\d+)\.(\d+)(?=\s|$)/.exec(eas.stdout);
  if (!installed) throw new Error('Cannot read a stable EAS CLI version');
  const comparison = installed.slice(1).map(Number)
    .map((part, index) => part - Number(minimum[index + 1])).find((part) => part !== 0) ?? 0;
  if (comparison < 0) {
    throw new Error(`EAS CLI ${installed.slice(1).join('.')} does not satisfy ${constraint}; update EAS_BIN before releasing`);
  }
  for (const [program, args, name] of [
    ['java', ['-version'], 'Java'],
    ['jarsigner', ['-help'], 'jarsigner'], ['keytool', ['-help'], 'keytool'],
  ]) {
    const result = spawnSync(program, args, { cwd: root, env: environment, stdio: 'ignore', timeout: 30000 });
    if (result.error || result.status !== 0) throw new Error(`${name} is unavailable`);
  }
  const disk = statfsSync(root);
  if (disk.bavail * disk.bsize < 15 * 1024 ** 3) throw new Error('Local builds require at least 15 GiB free disk space');
  if (totalmem() < 8 * 1024 ** 3) throw new Error('Local builds require at least 8 GiB RAM');
  return environment;
}

export function releaseFailure(stage) {
  return { stage, message: `Release failed during ${stage}; inspect the private host logs` };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    preflightRelease(fileURLToPath(new URL('..', import.meta.url)));
    console.log('Release preflight passed');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
