import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('..', import.meta.url));
const appConfig = JSON.parse(readFileSync(resolve(root, 'app.json'), 'utf8')).expo;
const output = resolve(root, 'android/app/build/outputs/bundle/release/app-release.aab');
const debugCertificateSha256 = 'fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c';
const required = [
  'ZEN_MODE_UPLOAD_STORE_FILE',
  'ZEN_MODE_UPLOAD_STORE_PASSWORD',
  'ZEN_MODE_UPLOAD_KEY_ALIAS',
  'ZEN_MODE_UPLOAD_KEY_PASSWORD',
  'ZEN_MODE_UPLOAD_CERT_SHA256',
];

function fail(message) {
  console.error(`Upload bundle build refused: ${message}`);
  process.exit(1);
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: 'utf8',
    ...options,
  });
  if (result.error) fail(`${command} could not start: ${result.error.message}`);
  if (result.status !== 0) {
    if (options.stdio !== 'inherit') {
      const details = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
      if (details) console.error(details);
    }
    fail(`${command} exited with status ${result.status}`);
  }
  return `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
}

function normalizeFingerprint(value) {
  return value.replaceAll(':', '').trim().toLowerCase();
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const missing = required.filter((name) => !process.env[name]?.trim());
if (missing.length) fail(`missing ${missing.join(', ')}`);

const storeFile = process.env.ZEN_MODE_UPLOAD_STORE_FILE.trim();
if (!isAbsolute(storeFile)) fail('ZEN_MODE_UPLOAD_STORE_FILE must be an absolute path');
if (!existsSync(storeFile)) fail('ZEN_MODE_UPLOAD_STORE_FILE does not exist');

const expectedFingerprint = normalizeFingerprint(process.env.ZEN_MODE_UPLOAD_CERT_SHA256);
if (!/^[0-9a-f]{64}$/.test(expectedFingerprint)) {
  fail('ZEN_MODE_UPLOAD_CERT_SHA256 must be a 64-digit SHA-256 certificate fingerprint');
}
if (expectedFingerprint === debugCertificateSha256) fail('the approved certificate is the Android debug certificate');

const keytoolOutput = run('keytool', [
  '-J-Duser.language=en',
  '-list',
  '-v',
  '-keystore',
  storeFile,
  '-alias',
  process.env.ZEN_MODE_UPLOAD_KEY_ALIAS,
  '-storepass:env',
  'ZEN_MODE_UPLOAD_STORE_PASSWORD',
]);
const keystoreMatch = keytoolOutput.match(/SHA256:\s*([0-9A-F:]{64,95})/i);
if (!keystoreMatch) fail('keytool did not report a SHA-256 certificate fingerprint');
const keystoreFingerprint = normalizeFingerprint(keystoreMatch[1]);
if (keystoreFingerprint !== expectedFingerprint) fail('keystore certificate does not match ZEN_MODE_UPLOAD_CERT_SHA256');

run(resolve(root, 'android/gradlew'), ['-p', resolve(root, 'android'), ':app:bundleRelease'], {
  stdio: 'inherit',
});
if (!existsSync(output)) fail('Gradle completed without producing app-release.aab');

const signatureVerification = run('jarsigner', ['-J-Duser.language=en', '-verify', output]);
if (!/jar verified/i.test(signatureVerification)) fail('jarsigner did not verify the built App Bundle');
const bundleCertificate = run('keytool', [
  '-J-Duser.language=en',
  '-printcert',
  '-jarfile',
  output,
]);
const bundleMatch = bundleCertificate.match(/SHA256:\s*([0-9A-F:]{64,95})/i);
if (!bundleMatch) fail('the built App Bundle did not expose a signer certificate');
if (normalizeFingerprint(bundleMatch[1]) !== expectedFingerprint) {
  fail('the built App Bundle signer does not match the approved certificate');
}

if (process.env.BUNDLETOOL_JAR?.trim()) {
  const bundletool = process.env.BUNDLETOOL_JAR.trim();
  if (!isAbsolute(bundletool) || !existsSync(bundletool)) fail('BUNDLETOOL_JAR must be an existing absolute path');
  const manifest = run('java', ['-jar', bundletool, 'dump', 'manifest', `--bundle=${output}`]);
  if (!new RegExp(`package="${escapeRegExp(appConfig.android.package)}"`).test(manifest)) {
    fail('bundletool reported an unexpected application ID');
  }
  if (!new RegExp(`android:versionName="${escapeRegExp(appConfig.version)}"`).test(manifest)) {
    fail('bundletool reported an unexpected Android version name');
  }
  if (!new RegExp(`android:versionCode="${appConfig.android.versionCode}"`).test(manifest)) {
    fail('bundletool reported an unexpected Android version code');
  }
}

const digest = createHash('sha256').update(readFileSync(output)).digest('hex');
console.log(`Verified upload bundle: ${output}`);
console.log(`AAB SHA-256: ${digest}`);
console.log(`Signer SHA-256: ${expectedFingerprint}`);
