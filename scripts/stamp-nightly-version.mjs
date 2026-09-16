import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export function releaseIdentity(version, versionCode) {
  if (!/^0\.1\.\d+$/.test(version)) throw new Error('Expected version 0.1.patch');
  if (!Number.isSafeInteger(versionCode) || versionCode < 1 || versionCode > 2100000000) {
    throw new Error('Invalid Android versionCode');
  }
  return { version, versionCode };
}

export function stampRelease(root, version, versionCode) {
  const reserved = releaseIdentity(version, versionCode);
  const appPath = resolve(root, 'app.json');
  const app = JSON.parse(readFileSync(appPath, 'utf8'));
  app.expo.version = reserved.version;
  app.expo.android.versionCode = reserved.versionCode;
  writeFileSync(appPath, `${JSON.stringify(app, null, 2)}\n`);
  const packagePath = resolve(root, 'package.json');
  const manifest = JSON.parse(readFileSync(packagePath, 'utf8'));
  manifest.version = reserved.version;
  writeFileSync(packagePath, `${JSON.stringify(manifest, null, 2)}\n`);
  // npm ci verifies the root version recorded in the lockfile as well.
  const lockPath = resolve(root, 'package-lock.json');
  try {
    const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
    lock.version = reserved.version;
    if (lock.packages?.['']) lock.packages[''].version = reserved.version;
    writeFileSync(lockPath, `${JSON.stringify(lock, null, 2)}\n`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  return reserved;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = fileURLToPath(new URL('..', import.meta.url));
  console.log(JSON.stringify(stampRelease(root, process.argv[2], Number(process.argv[3]))));
}
