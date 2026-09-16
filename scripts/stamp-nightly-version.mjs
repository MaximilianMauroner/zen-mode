import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Date-based nightly identity for the Play Internal track.
 * versionCode must rise on every upload, so each nightly gets YYYYMMDDNN.
 * The value stays below the 2100000000 Play limit until year 2100.
 */
export function nightlyVersionCode(date = new Date(), runNumber = 1) {
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  const sequence = String(Math.trunc(Math.abs(runNumber)) % 100).padStart(2, '0');
  return Number(`${year}${month}${day}${sequence}`);
}

export function nightlyVersionName(baseVersion, date = new Date()) {
  const year = date.getUTCFullYear();
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  return `${baseVersion}-nightly.${year}${month}${day}`;
}

const isMain = process.argv[1] === fileURLToPath(import.meta.url);

if (isMain) {
  const root = fileURLToPath(new URL('..', import.meta.url));
  const appJsonPath = resolve(root, 'app.json');
  const appJson = JSON.parse(readFileSync(appJsonPath, 'utf8'));
  const runNumber = Number(process.env.GITHUB_RUN_NUMBER ?? '1');
  const now = new Date();
  const versionCode = nightlyVersionCode(now, Number.isFinite(runNumber) ? runNumber : 1);
  const baseVersion = appJson.expo.version.split('-')[0];
  appJson.expo.version = nightlyVersionName(baseVersion, now);
  appJson.expo.android.versionCode = versionCode;
  writeFileSync(appJsonPath, `${JSON.stringify(appJson, null, 2)}\n`);
  console.log(`Stamped nightly ${appJson.expo.version} (${versionCode})`);
}
