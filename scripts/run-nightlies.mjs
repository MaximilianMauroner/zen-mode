#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';

if (process.argv.length !== 4) throw new Error('Usage: node scripts/run-nightlies.mjs MOODINATOR_REPO ZEN_MODE_REPO');
for (const root of process.argv.slice(2)) {
  const result = spawnSync(process.execPath, [resolve(root, 'scripts/nightly-release.mjs'), 'run'], {
    cwd: resolve(root), stdio: 'inherit',
  });
  // A failed app does not stop the other app's nightly.
  if (result.error) console.error(result.error.message);
  if (result.error || result.status !== 0) process.exitCode = 1;
}
