#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

if (process.platform !== 'darwin' || Intl.DateTimeFormat().resolvedOptions().timeZone !== 'Europe/Vienna') {
  throw new Error('LaunchAgent requires this Mac to use the Europe/Vienna time zone');
}
if (process.argv.length !== 4) throw new Error('Usage: node scripts/write-nightly-launch-agent.mjs MOODINATOR_REPO ZEN_MODE_REPO');
const root = fileURLToPath(new URL('..', import.meta.url));
const artifacts = resolve(root, '.agents/artifacts');
const logs = resolve(homedir(), 'Library/Logs/lab4code-nightlies');
mkdirSync(artifacts, { recursive: true });
mkdirSync(logs, { recursive: true });
const xml = (value) => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const args = [process.execPath, resolve(root, 'scripts/run-nightlies.mjs'), ...process.argv.slice(2).map((path) => resolve(path))];
const variables = ['PATH', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_BUNDLETOOL_JAR', 'PLAY_SERVICE_ACCOUNT_KEY_PATH', 'EAS_BIN', 'RELEASE_ARTIFACTS_DIR'];
const environment = variables.filter((key) => process.env[key]).map((key) => `<key>${key}</key><string>${xml(process.env[key])}</string>`).join('\n');
const target = resolve(artifacts, 'net.lab4code.nightly.plist');
writeFileSync(target, `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>net.lab4code.nightly</string>
<key>ProgramArguments</key><array>${args.map((arg) => `<string>${xml(arg)}</string>`).join('')}</array>
<key>EnvironmentVariables</key><dict>${environment}</dict>
<key>WorkingDirectory</key><string>${xml(root)}</string>
<key>StartCalendarInterval</key><dict><key>Hour</key><integer>23</integer><key>Minute</key><integer>0</integer></dict>
<key>StandardOutPath</key><string>${xml(resolve(logs, 'nightly.log'))}</string>
<key>StandardErrorPath</key><string>${xml(resolve(logs, 'nightly.error.log'))}</string>
</dict></plist>\n`, { mode: 0o600 });
console.log(target);
