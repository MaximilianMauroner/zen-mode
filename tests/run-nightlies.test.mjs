import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('nightly runner invokes both release entrypoints without updating their checkouts', () => {
  const directory = mkdtempSync(join(tmpdir(), 'nightlies-runner-'));
  try {
    const paths = ['moodinator', 'zen-mode'].map((name) => {
      const root = join(directory, name);
      mkdirSync(join(root, 'scripts'), { recursive: true });
      writeFileSync(join(root, 'scripts/nightly-release.mjs'),
        `import { writeFileSync } from 'node:fs';\nwriteFileSync(${JSON.stringify(join(root, 'called'))}, 'run');\n`);
      return root;
    });
    const script = fileURLToPath(new URL('../scripts/run-nightlies.mjs', import.meta.url));
    const result = spawnSync(process.execPath, [script, ...paths], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    for (const root of paths) assert.equal(readFileSync(join(root, 'called'), 'utf8'), 'run');
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
