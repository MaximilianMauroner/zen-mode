import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const approvedCertificates = {
  moodinator: '73bc862a96d8768bb44fb152a7ada91f7d91c04013b72ca756ef3e0cd2a98132',
  'zen-mode': '46ab4a5bdb4b5e57ccf0c35570076eb58c3adf02db04aa04bbbcfd1eda082d59',
};

function output(program, args) {
  const result = spawnSync(program, args, { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${program} artifact verification failed: ${result.stderr}`);
  return result.stdout.trim();
}

export function assertArtifactIdentity(actual, expected, app) {
  for (const key of ['package', 'version', 'versionCode']) {
    if (actual[key] !== expected[key]) throw new Error(`${key} mismatch: expected ${expected[key]}, got ${actual[key]}`);
  }
  if (actual.certificate !== approvedCertificates[app]) throw new Error('Artifact does not use the approved upload certificate');
}

export function verifyReleaseArtifacts(apk, aab, expected, app) {
  const sdk = process.env.ANDROID_HOME ?? process.env.ANDROID_SDK_ROOT;
  const bundletool = process.env.ANDROID_BUNDLETOOL_JAR;
  if (!sdk || !bundletool || !existsSync(bundletool)) {
    throw new Error('Set ANDROID_HOME and ANDROID_BUNDLETOOL_JAR for artifact verification');
  }
  const versions = readdirSync(join(sdk, 'build-tools'), { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && /^\d/.test(entry.name))
    .map((entry) => entry.name).sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  if (!versions.length) throw new Error('Android SDK build-tools are missing');
  const tools = join(sdk, 'build-tools', versions[0]);
  const badging = output(join(tools, 'aapt'), ['dump', 'badging', apk]);
  const match = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/);
  if (!match) throw new Error('Could not read APK identity');
  const apkSigner = output(join(tools, 'apksigner'), ['verify', '--print-certs', apk]);
  const apkCertificate = apkSigner.match(/certificate SHA-256 digest:\s*([a-f0-9]+)/i)?.[1]?.toLowerCase();
  assertArtifactIdentity({ package: match[1], versionCode: Number(match[2]), version: match[3], certificate: apkCertificate }, expected, app);
  const java = ['-Xmx512m', '-jar', bundletool];
  output('java', [...java, 'validate', `--bundle=${aab}`]);
  const value = (attribute) => output('java', [...java, 'dump', 'manifest', `--bundle=${aab}`, `--xpath=/manifest/@${attribute}`]);
  const signature = output('jarsigner', ['-J-Duser.language=en', '-J-Duser.country=US', '-verify', aab]);
  if (!signature.includes('jar verified.')) throw new Error('AAB signature verification failed');
  const aabSigner = output('keytool', ['-J-Duser.language=en', '-J-Duser.country=US', '-printcert', '-jarfile', aab]);
  const aabCertificate = aabSigner.match(/SHA256:\s*([a-f0-9:]+)/i)?.[1]?.replaceAll(':', '').toLowerCase();
  assertArtifactIdentity({ package: value('package'), version: value('android:versionName'),
    versionCode: Number(value('android:versionCode')), certificate: aabCertificate }, expected, app);
  console.log(`Verified APK and AAB: ${expected.version} (${expected.versionCode}), approved upload certificate`);
}
