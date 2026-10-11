#!/usr/bin/env node
// Real-app QA for Zen Mode: a source-matched QA build, one explicit device,
// authentic target apps, and evidence that does not disturb accessibility.
// Usage and journeys: docs/real-app-testing.md.

import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  appendFileSync, copyFileSync, createWriteStream, existsSync, mkdirSync, mkdtempSync,
  readFileSync, readdirSync, rmSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import * as model from './model.mjs';

// The checkout under test is the working directory, so the tool can build a PR
// head from another checkout. Evidence stays with that checkout, outside Git.
const root = process.cwd();
const initScript = fileURLToPath(new URL('qa-build-type.init.gradle', import.meta.url));
const artifacts = join(root, '.agents/artifacts/real-app-qa');
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
const quote = (value) => `'${String(value).replaceAll("'", "'\\''")}'`;

function run(program, args, { cwd = root, env = process.env, allowFailure = false, encoding = 'utf8' } = {}) {
  const result = spawnSync(program, args, { cwd, env, encoding, maxBuffer: 256 * 1024 * 1024 });
  if (result.error) throw result.error;
  if (!allowFailure && result.status !== 0) {
    throw new Error(`${basename(program)} ${args.join(' ')} failed (${result.status})\n${result.stderr}`);
  }
  return result;
}

function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function sdkRoot() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error('Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK.');
  return sdk;
}

function buildTool(name) {
  const tools = join(sdkRoot(), 'build-tools');
  const version = readdirSync(tools).filter((entry) => /^\d/.test(entry))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
    .find((entry) => existsSync(join(tools, entry, name)));
  if (!version) throw new Error(`Android SDK build-tools have no ${name}`);
  return join(tools, version, name);
}

function apkFacts(path) {
  return {
    file: basename(path),
    sha256: sha256File(path),
    ...model.parseBadging(run(buildTool('aapt'), ['dump', 'badging', path]).stdout),
    signers: model.parseSignerDigests(run(buildTool('apksigner'), ['verify', '--print-certs', path]).stdout),
  };
}

// --- build -----------------------------------------------------------------

function javaHome() {
  const home = process.env.JAVA_HOME ||
    (process.platform === 'darwin' ? run('/usr/libexec/java_home', ['-v', '21']).stdout.trim() : '');
  const java = home && spawnSync(join(home, 'bin/java'), ['-version'], { encoding: 'utf8' });
  if (!java || java.error || java.status !== 0) {
    throw new Error(`JAVA_HOME ${JSON.stringify(home)} does not run java. On macOS use JAVA_HOME="$(/usr/libexec/java_home -v 21)".`);
  }
  return { home, version: java.stderr.split('\n')[0].trim() };
}

function buildEnvironment(java) {
  const env = { ...process.env };
  // QA builds never see upload-key or EAS signing inputs.
  for (const name of Object.keys(env)) {
    if (name.startsWith('ZEN_MODE_UPLOAD_') || name === 'EAS_BUILD_WORKINGDIR') delete env[name];
  }
  return {
    ...env,
    JAVA_HOME: java.home,
    // Gradle's bundling step runs `node`; use the same runtime as this script.
    PATH: [dirname(process.execPath), env.PATH].join(delimiter),
    CMAKE_BUILD_PARALLEL_LEVEL: '1',
    EXPO_NO_GIT_STATUS: '1',
    CI: '1',
  };
}

function logged(log, program, args, options) {
  log.write(`\n$ ${program} ${args.join(' ')}\n`);
  return new Promise((accept, reject) => {
    const child = spawn(program, args, { ...options, stdio: ['ignore', 'pipe', 'pipe'] });
    for (const stream of [child.stdout, child.stderr]) {
      stream.on('data', (chunk) => { process.stdout.write(chunk); log.write(chunk); });
    }
    child.once('error', reject);
    child.once('close', (code) => code === 0 ? accept() : reject(new Error(`${basename(program)} failed (${code}); see ${log.path}`)));
  });
}

function resourceSnapshot() {
  const disk = run('df', ['-h', root]).stdout.trim();
  const memory = process.platform === 'darwin'
    ? run('memory_pressure', ['-Q'], { allowFailure: true }).stdout.trim()
    : run('free', ['-m'], { allowFailure: true }).stdout.trim();
  return `${disk}\n${memory}`;
}

async function build(options) {
  const appConfig = existsSync(join(root, 'app.json')) && JSON.parse(readFileSync(join(root, 'app.json'), 'utf8'));
  if (appConfig?.expo?.android?.package !== model.PLAY_PACKAGE) {
    throw new Error(`Run build from the root of a Zen Mode checkout, not ${root}`);
  }
  const git = (...args) => run('git', args).stdout.trim();
  const changes = git('status', '--porcelain');
  if (changes) throw new Error(`Commit or remove these changes so the build matches its source:\n${changes}`);
  const head = git('rev-parse', 'HEAD');
  const short = head.slice(0, 7);
  const identity = model.qaIdentity(options.id ?? short);
  const abi = options.abi ?? 'arm64-v8a';
  if (!['arm64-v8a', 'x86_64'].includes(abi)) throw new Error('--abi must be arm64-v8a or x86_64');
  const expoCli = join(root, 'node_modules/expo/bin/cli');
  if (!existsSync(expoCli)) throw new Error('Dependencies are missing. Run npm ci first.');
  sdkRoot();
  const java = javaHome();
  const env = buildEnvironment(java);

  // Keep the evidence of every earlier attempt, failed or not.
  const dir = join(artifacts, 'builds', identity.id);
  if (existsSync(dir)) throw new Error(`${dir} already exists. Pass a new --id to keep its evidence.`);
  mkdirSync(join(dir, 'res/values'), { recursive: true });
  writeFileSync(join(dir, 'res/values/strings.xml'), [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<resources>',
    `  <string name="app_name">${identity.label}</string>`,
    `  <string name="zen_guard_accessibility_service_label">${identity.serviceLabel}</string>`,
    '</resources>',
    '',
  ].join('\n'));

  const log = createWriteStream(join(dir, 'build.log'));
  log.write(`source ${head}\n${java.version}\nJAVA_HOME=${java.home}\n${resourceSnapshot()}\n`);
  try {
    await logged(log, process.execPath, [expoCli, 'prebuild', '--platform', 'android', '--no-install', '--clean'], { cwd: root, env });
    await logged(log, './gradlew', [
      '--no-daemon', '--no-parallel', '--max-workers=1',
      '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m',
      '-Pkotlin.compiler.execution.strategy=in-process',
      `-PreactNativeArchitectures=${abi}`,
      '--init-script', initScript,
      `-PzenQaId=${identity.id}`,
      `-PzenQaResDir=${join(dir, 'res')}`,
      `-PzenQaVersionSuffix=-qa.${short}`,
      ':app:assembleQa',
    ], { cwd: join(root, 'android'), env });
  } finally {
    log.end();
  }
  const generatedChanges = git('status', '--porcelain');
  if (generatedChanges) throw new Error(`Prebuild changed tracked source files:\n${generatedChanges}`);

  const apkName = `zen-qa${identity.id}-${short}.apk`;
  copyFileSync(join(root, 'android/app/build/outputs/apk/qa/app-qa.apk'), join(dir, apkName));
  const apk = apkFacts(join(dir, apkName));
  const problems = [
    apk.package !== identity.package && `package ${apk.package}`,
    apk.label !== identity.label && `label ${apk.label}`,
    !apk.versionName?.endsWith(`-qa.${short}`) && `versionName ${apk.versionName}`,
    apk.debuggable && 'debuggable',
    apk.signers.join() !== model.QA_SIGNER_SHA256 && `signer ${apk.signers.join(', ')}`,
  ].filter(Boolean);
  if (problems.length) throw new Error(`QA APK identity is wrong: ${problems.join('; ')}`);

  const record = {
    builtAt: new Date().toISOString(),
    source: { head, branch: git('rev-parse', '--abbrev-ref', 'HEAD'), committedAt: git('log', '-1', '--format=%cI') },
    identity,
    abi,
    java: java.version,
    controls: 'gradle --no-daemon --no-parallel --max-workers=1 -Xmx1536m, kotlin in-process, ' +
      'CMake job pools compile=1 link=1, CMAKE_BUILD_PARALLEL_LEVEL=1, metro --max-workers 1, one ABI',
    apk: { file: apkName, sha256: apk.sha256, package: apk.package, versionName: apk.versionName,
      versionCode: apk.versionCode, label: apk.label, signer: apk.signers[0] },
  };
  writeFileSync(join(dir, 'build.json'), `${JSON.stringify(record, null, 2)}\n`);
  console.log(`\nBuilt ${apk.package} ${apk.versionName} (${apk.versionCode}) from ${head}\n${join(dir, apkName)}\nsha256 ${apk.sha256}`);
}

// --- device ----------------------------------------------------------------

function loadBuild(id) {
  const identity = model.qaIdentity(id);
  const dir = join(artifacts, 'builds', identity.id);
  const recordPath = join(dir, 'build.json');
  if (!existsSync(recordPath)) throw new Error(`No QA build ${identity.id}. Run: build --id ${identity.id}`);
  const record = JSON.parse(readFileSync(recordPath, 'utf8'));
  const apk = join(dir, record.apk.file);
  if (record.apk.package !== identity.package || sha256File(apk) !== record.apk.sha256) {
    throw new Error(`${apk} does not match ${recordPath}; rebuild it`);
  }
  return { identity, record, apk };
}

function openDevice(serial) {
  if (!serial) throw new Error('Pass --serial. No device is selected by default.');
  const adbPath = process.env.ADB ?? join(sdkRoot(), 'platform-tools/adb');
  const state = model.deviceState(run(adbPath, ['devices']).stdout, serial);
  if (state !== 'device') throw new Error(`${serial} is ${state ?? 'not attached'} (adb devices)`);
  const raw = (args, options) => run(adbPath, ['-s', serial, ...args], options);
  const adb = (...args) => raw(args).stdout;
  const shell = (...args) => adb('shell', ...args);
  const props = Object.fromEntries([...shell('getprop').matchAll(/^\[([^\]]+)\]: \[(.*)\]$/gm)]
    .map((match) => [match[1], match[2]]));
  const identity = {
    serial,
    manufacturer: props['ro.product.manufacturer'],
    model: props['ro.product.model'],
    android: props['ro.build.version.release'],
    sdk: Number(props['ro.build.version.sdk']),
    abi: props['ro.product.cpu.abi'],
    fingerprint: props['ro.build.fingerprint'],
    emulator: serial.startsWith('emulator-') && (props['ro.kernel.qemu'] === '1' || props['ro.boot.qemu'] === '1'),
    avd: props['ro.boot.qemu.avd_name'] ?? null,
  };
  return { serial, raw, adb, shell, identity };
}

function requireDisposable(device, allowPhysical) {
  if (device.identity.emulator || allowPhysical) return;
  throw new Error(`${device.serial} is not an emulator. This command changes device state; ` +
    'pass --allow-physical only for an owned test device.');
}

function evidenceLog(build, device) {
  const dir = join(artifacts, 'runs', `${build.identity.id}-${device.serial.replace(/[^\w.-]/g, '_')}`);
  mkdirSync(dir, { recursive: true });
  const file = join(dir, 'evidence.jsonl');
  // The device and QA build that every record is evidence for.
  const subject = {
    device: device.identity,
    build: { id: build.identity.id, package: build.identity.package, source: build.record.source.head,
      versionName: build.record.apk.versionName, apkSha256: build.record.apk.sha256 },
  };
  return {
    dir,
    write(command, details) {
      appendFileSync(file, `${JSON.stringify({ at: new Date().toISOString(), command, ...subject, ...details })}\n`);
    },
  };
}

function installedPackage(device, packageName) {
  const paths = [...device.raw(['shell', 'pm', 'path', packageName], { allowFailure: true }).stdout
    .matchAll(/^package:(.+)$/gm)].map((match) => match[1].trim());
  if (!paths.length) return null;
  return {
    package: packageName,
    installed: true,
    basePath: paths.find((path) => path.endsWith('/base.apk')) ?? paths[0],
    apkCount: paths.length,
    ...model.parsePackageDump(device.shell('dumpsys', 'package', packageName), packageName),
  };
}

function installedSigners(device, path) {
  const dir = mkdtempSync(join(tmpdir(), 'zen-qa-pull-'));
  try {
    const local = join(dir, 'base.apk');
    device.adb('pull', path, local);
    return { baseSha256: sha256File(local), signers: apkFacts(local).signers };
  } catch (error) {
    return { signatureError: error.message.split('\n')[0] };
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Install facts and signers of a target, read from the installed APK now. */
function targetStatus(device, key) {
  const packageName = model.TARGETS[key].package;
  const info = installedPackage(device, packageName);
  if (!info) return { package: packageName, installed: false };
  // An app that only uses the name of the package that started the install is not a system app.
  const initiatorSystem = Boolean(info.initiator && installedPackage(device, info.initiator)?.system);
  return { ...info, initiatorSystem, ...installedSigners(device, info.basePath) };
}

function accessStatus(device, identity) {
  return {
    enabled: model.serviceEnabled(device.shell('settings', 'get', 'secure', 'enabled_accessibility_services'),
      identity.serviceComponent),
    bound: model.serviceBound(device.shell('dumpsys', 'accessibility'), identity),
  };
}

function qaStatus(device, build) {
  const info = installedPackage(device, build.identity.package);
  if (!info) return { installed: false };
  const deviceSha256 = device.shell('sha256sum', info.basePath).trim().split(/\s+/)[0];
  return {
    installed: true,
    versionName: info.versionName,
    versionCode: info.versionCode,
    matchesBuild: deviceSha256 === build.record.apk.sha256,
    access: accessStatus(device, build.identity),
  };
}

async function waitForBinding(device, identity, bound) {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    if (model.serviceBound(device.shell('dumpsys', 'accessibility'), identity) === bound) return;
    await sleep(500);
  }
  throw new Error(`QA service did not become ${bound ? 'bound' : 'unbound'} within 15 s`);
}

function install(options) {
  const build = loadBuild(options.build);
  const device = openDevice(options.serial);
  requireDisposable(device, options['allow-physical']);
  device.adb('install', '-r', build.apk);
  const qa = qaStatus(device, build);
  if (!qa.matchesBuild) throw new Error('The installed QA APK differs from the local build');
  evidenceLog(build, device).write('install', { qa });
  console.log(`Installed ${build.identity.package} ${qa.versionName} on ${device.serial}; APK hash matches build`);
}

function installTarget(options) {
  if (!options.apk?.length) throw new Error('Pass --apk for the base APK and again for each split');
  if (!options.source) throw new Error('Pass --source with the official download URL or store');
  const build = loadBuild(options.build);
  const device = openDevice(options.serial);
  requireDisposable(device, options['allow-physical']);
  const paths = options.apk.map((file) => resolve(file));
  const files = paths.map(apkFacts);
  const target = model.checkTargetApks(files);
  const details = { source: options.source, target, files: files.map(({ file, sha256, split }) => ({ file, sha256, split })) };
  const existing = installedPackage(device, target.package);
  if (existing && existing.versionCode !== target.versionCode) {
    throw new Error(`${target.package} ${existing.versionName} (${existing.versionCode}) is already installed. ` +
      'This tool does not replace or downgrade installed apps.');
  }
  if (existing && installedSigners(device, existing.basePath).signers?.join() !== target.signers.join()) {
    throw new Error(`The installed ${target.package} has a different or unreadable signer`);
  }
  if (!existing) device.adb(paths.length === 1 ? 'install' : 'install-multiple', ...paths);
  const installed = installedPackage(device, target.package);
  if (installed?.versionCode !== target.versionCode) throw new Error(`${target.package} did not install`);
  evidenceLog(build, device).write('install-target', { ...details, alreadyInstalled: Boolean(existing) });
  console.log(`${existing ? 'Already installed' : 'Installed'} ${target.package} ${target.versionName} ` +
    `(${target.versionCode}); signer ${target.signers.join(', ')}`);
}

function inventory(options) {
  const build = loadBuild(options.build);
  const device = openDevice(options.serial);
  const signedIn = options['signed-in'] ?? [];
  for (const key of signedIn) {
    if (!model.TARGETS[key]?.signIn) throw new Error(`--signed-in accepts ${Object.keys(model.TARGETS).filter((name) => model.TARGETS[name].signIn).join(', ')}`);
  }
  const qa = qaStatus(device, build);
  const targets = Object.fromEntries(Object.keys(model.TARGETS).map((key) => [key, targetStatus(device, key)]));
  const journeys = model.JOURNEYS.map((journey) => model.journeyReadiness(journey, { qa, targets, signedIn }));
  evidenceLog(build, device).write('inventory', { qa, targets, signedIn, journeys });

  const { identity } = device;
  console.log(`Device ${identity.serial}: ${identity.manufacturer} ${identity.model}, Android ${identity.android} (API ${identity.sdk}), ` +
    `${identity.abi}, ${identity.emulator ? `emulator ${identity.avd}` : 'physical'}\n${identity.fingerprint}`);
  console.log(`QA ${build.identity.package}: ${qa.installed
    ? `${qa.versionName}, matches build ${qa.matchesBuild}, service enabled ${qa.access.enabled}, bound ${qa.access.bound}`
    : 'not installed'} (source ${build.record.source.head})`);
  console.table(Object.fromEntries(Object.entries(targets).map(([key, target]) => [key, {
    package: target.package,
    version: target.installed ? `${target.versionName} (${target.versionCode})` : 'not installed',
    installer: target.installer ?? (target.system ? 'system image' : ''),
    signer: target.signers?.join(', ') ?? target.signatureError ?? '',
    authentic: model.targetProblem(key, target) ?? 'yes',
  }])));
  console.table(Object.fromEntries(journeys.map((journey) => [journey.id, {
    pr: `#${journey.pr} (issue #${journey.issue})`, status: journey.status, reasons: journey.reasons.join('; '),
  }])));
}

async function access(action, options) {
  if (!['enable', 'disable', 'status'].includes(action)) throw new Error('Use: access enable|disable|status');
  const build = loadBuild(options.build);
  const device = openDevice(options.serial);
  const { identity } = build;
  if (action !== 'status') {
    requireDisposable(device, options['allow-physical']);
    if (!installedPackage(device, identity.package)) throw new Error(`${identity.package} is not installed`);
    const before = device.shell('settings', 'get', 'secure', 'enabled_accessibility_services').trim();
    const after = model.editServiceList(before, identity.serviceComponent, action === 'enable');
    if (after) device.shell('settings', 'put', 'secure', 'enabled_accessibility_services', after);
    else device.shell('settings', 'delete', 'secure', 'enabled_accessibility_services');
    if (action === 'enable') device.shell('settings', 'put', 'secure', 'accessibility_enabled', '1');
    await waitForBinding(device, identity, action === 'enable');
    evidenceLog(build, device).write(`access ${action}`, { before, after });
  }
  const status = accessStatus(device, identity);
  console.log(`${identity.serviceComponent}: enabled ${status.enabled}, bound ${status.bound}`);
}

/**
 * Samples the screen state without UI Automator, which suspends accessibility
 * services while it inspects the hierarchy. Only screencap, dumpsys and logcat run here.
 */
async function observe(options) {
  const build = loadBuild(options.build);
  const device = openDevice(options.serial);
  const { identity } = build;
  const label = options.label ?? '';
  if (!/^[a-z0-9][a-z0-9-]{0,60}$/.test(label)) throw new Error('Pass --label with lowercase letters, digits and dashes');
  const { seconds, interval, settle } = model.observeTiming(options);
  const app = options['open-url'] ? model.TARGETS[options.app] : null;
  if (options['open-url'] && !app) throw new Error(`--open-url needs --app ${Object.keys(model.TARGETS).join('|')}`);
  // Only an observation that opens the target itself is judged; a manual one is OBSERVED.
  if (options.expect && !app) throw new Error('--expect needs --open-url and --app');
  if (options.expect && !model.EXPECTATIONS.includes(options.expect)) {
    throw new Error(`--expect must be ${model.EXPECTATIONS.join(', ')}`);
  }

  const qa = qaStatus(device, build);
  if (!qa.installed || !qa.matchesBuild) {
    throw new Error(`BLOCKED: ${identity.package} on ${device.serial} is not the APK of build ${identity.id}. Run install.`);
  }
  if (!qa.access.enabled) throw new Error('BLOCKED: QA service is not enabled. Run access enable.');
  const log = evidenceLog(build, device);
  let target = null;
  if (app) {
    requireDisposable(device, options['allow-physical']);
    // Verify the APK installed now; an earlier inventory can describe another install.
    target = targetStatus(device, options.app);
    const problem = model.targetProblem(options.app, target);
    if (problem) throw new Error(`BLOCKED: ${options.app} ${problem}`);
  }

  // Let the service rebind and settle after any earlier hierarchy dump or agent snapshot.
  await waitForBinding(device, identity, true);
  await sleep(settle * 1000);

  // The random part keeps names apart within one second. Files are only created,
  // never replaced, so a name that exists stops the observation before its record.
  const stamp = new Date().toISOString().replace(/[-:]/g, '').slice(0, 15);
  const name = `${stamp}-${label}-${randomUUID().slice(0, 8)}`;
  const save = (file, data) => writeFileSync(join(log.dir, file), data, { flag: 'wx' });
  const screenshots = [];
  const screenshot = (phase) => {
    const file = `${name}-${screenshots.length.toString().padStart(2, '0')}-${phase}.png`;
    save(file, device.raw(['exec-out', 'screencap', '-p'], { encoding: 'buffer' }).stdout);
    screenshots.push(file);
  };
  let previous = null;
  const sample = (ms) => {
    const windows = model.parseWindowRecords(device.shell('dumpsys', 'window', 'windows'));
    const current = { ms, overlay: model.overlayShown(windows, identity.package),
      foreground: model.foregroundPackage(windows),
      bound: model.serviceBound(device.shell('dumpsys', 'accessibility'), identity) };
    if (options['audio-package']) {
      current.audio = model.mediaStates(device.shell('dumpsys', 'media_session'), options['audio-package']);
    }
    if (!previous) screenshot('start');
    else if (previous.overlay !== current.overlay || previous.foreground !== current.foreground) {
      screenshot(`${ms}ms-${current.overlay ? 'overlay' : 'no-overlay'}`);
    }
    previous = current;
    return current;
  };
  const open = app ? () => {
    if (options.expect === 'cleared') {
      // The overlay can close during the screenshot and dumps of the first sample. Check again,
      // binding first and windows last, with no other work before the launch. This narrows the
      // gap but does not close it: the rule can still end during the launch.
      const bound = model.serviceBound(device.shell('dumpsys', 'accessibility'), identity);
      const windows = model.parseWindowRecords(device.shell('dumpsys', 'window', 'windows'));
      const overlay = model.overlayShown(windows, identity.package);
      const foreground = model.foregroundPackage(windows);
      if (!bound || !overlay || foreground !== app.package) {
        throw new Error(`BLOCKED: right before the launch the bound QA overlay was not over ${app.package} ` +
          `(bound ${bound}, overlay ${overlay}, foreground ${foreground}). The URL did not open.`);
      }
    }
    const launch = device.shell(`am start -W -a android.intent.action.VIEW -d ${quote(options['open-url'])} -p ${app.package}`);
    if (/^Error:/m.test(launch)) throw new Error(`BLOCKED: ${options.app} did not open the URL\n${launch}`);
  } : null;

  const logStart = device.shell(`date ${quote('+%m-%d %H:%M:%S.000')}`).trim();
  const samples = await model.takeSamples({
    seconds, interval, expect: options.expect, foreground: app?.package, sample, open, sleep,
  });
  screenshot('end');

  const logcat = device.adb('logcat', '-d', '-v', 'threadtime', '-T', logStart,
    'ZenGuardTrace:V', 'ReactNativeJS:V', 'AndroidRuntime:E', 'libc:F', 'DEBUG:F', '*:S');
  const crash = device.adb('logcat', '-b', 'crash', '-d', '-v', 'threadtime', '-T', logStart);
  save(`${name}.logcat.txt`, `${logcat}\n--- crash buffer ---\n${crash}`);
  save(`${name}.timeline.json`, `${JSON.stringify(samples, null, 2)}\n`);
  const crashed = crash.includes(identity.package);
  const bound = samples.every((sample) => sample.bound);
  const targetInFront = !app || samples.every((sample) => sample.foreground === app.package);
  const result = model.observationResult(samples, { expect: options.expect, foreground: app?.package, crashed });
  log.write('observe', {
    label, result, expect: options.expect ?? null, openUrl: options['open-url'] ?? null, app: options.app ?? null,
    target, audioPackage: options['audio-package'] ?? null, boundThroughout: bound, targetInFront, crashed,
    firstOverlayMs: samples.find((sample) => sample.overlay)?.ms ?? null, screenshots,
    timeline: `${name}.timeline.json`, logcat: `${name}.logcat.txt`,
  });
  const overlayChanges = samples.filter((sample, index) => index === 0 || sample.overlay !== samples[index - 1].overlay)
    .map((sample) => `${sample.ms}ms ${sample.overlay ? 'overlay' : 'no overlay'} (${sample.foreground})`);
  const notes = [
    !bound && 'service was not bound for every sample',
    !targetInFront && `${options.app} was not in front for every sample`,
    crashed && 'QA app crashed',
    result === 'OBSERVED' && options.expect === 'no-overlay' &&
      'the tool cannot read the active rules, so accept or reject the absent overlay by hand',
    result === 'OBSERVED' && options.expect === 'cleared' &&
      'the tool cannot tell a dismissal from a rule that ended, so accept or reject the cleared overlay by hand',
  ].filter(Boolean);
  console.log(`${result} ${label}: ${overlayChanges.join(' -> ')}${notes.map((note) => `; ${note}`).join('')}\n${log.dir}`);
  if (['FAIL', 'INVALID'].includes(result)) process.exitCode = 1;
}

// --- entry -----------------------------------------------------------------

const usage = 'Usage: node scripts/real-app-qa/cli.mjs <build|install|install-target|inventory|access|observe> [options]\n' +
  'observe --expect no-overlay and --expect cleared never pass. The tool cannot read which rules are active,\n' +
  'so an absent overlay is OBSERVED for the operator. An overlay where none is expected is FAIL.\n' +
  'See docs/real-app-testing.md.';
let parsed;
try {
  parsed = parseArgs({
    allowPositionals: true,
    options: {
      id: { type: 'string' },
      abi: { type: 'string' },
      build: { type: 'string' },
      serial: { type: 'string' },
      'allow-physical': { type: 'boolean' },
      apk: { type: 'string', multiple: true },
      source: { type: 'string' },
      'signed-in': { type: 'string', multiple: true },
      label: { type: 'string' },
      seconds: { type: 'string' },
      interval: { type: 'string' },
      settle: { type: 'string' },
      'open-url': { type: 'string' },
      app: { type: 'string' },
      expect: { type: 'string' },
      'audio-package': { type: 'string' },
    },
  });
} catch (error) {
  console.error(`${error.message}\n${usage}`);
  process.exit(2);
}
const { values: options, positionals } = parsed;

const commands = {
  build: () => build(options),
  install: () => install(options),
  'install-target': () => installTarget(options),
  inventory: () => inventory(options),
  access: () => access(positionals[1], options),
  observe: () => observe(options),
};

const command = commands[positionals[0]];
if (!command) {
  console.error(usage);
  process.exit(2);
}
try {
  await command();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
