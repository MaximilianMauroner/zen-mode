#!/usr/bin/env node
/**
 * Runs the zen-guard Kotlin unit tests.
 *
 * The Gradle project lives in the `android/` folder that `expo prebuild`
 * generates, and that folder is not checked in. Generate it when it is
 * missing, then hand over to Gradle. Extra arguments go to Gradle, so
 * `npm run test:native -- --tests '*AdultSitePolicyTest*'` narrows a run.
 */
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const android = join(root, 'android');

if (!process.env.ANDROID_HOME && !process.env.ANDROID_SDK_ROOT) {
  console.error('Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK before running the native tests.');
  process.exit(1);
}

function run(program, args, cwd) {
  const result = spawnSync(program, args, { cwd, stdio: 'inherit', shell: process.platform === 'win32' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}

if (!existsSync(join(android, 'gradlew'))) {
  console.log('No android/ folder. Generating it with expo prebuild.');
  run('npx', ['expo', 'prebuild', '--platform', 'android', '--no-install'], root);
}

run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', [':zen-guard:test', ...process.argv.slice(2)], android);
