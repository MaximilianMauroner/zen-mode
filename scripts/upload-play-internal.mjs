#!/usr/bin/env node
import { createSign } from 'node:crypto';
import { createReadStream, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { releaseIdentity } from './stamp-nightly-version.mjs';

const packages = {
  moodinator: 'com.lab4code.moodinator',
  'zen-mode': 'com.lab4code.zenmode',
};
const tokenUrl = 'https://oauth2.googleapis.com/token';
const publisherUrl = 'https://androidpublisher.googleapis.com';
const scope = 'https://www.googleapis.com/auth/androidpublisher';

function serviceAccount(keyPath) {
  if (!keyPath) throw new Error('Set PLAY_SERVICE_ACCOUNT_KEY_PATH to the authorized Play API key');
  let key;
  try {
    key = JSON.parse(readFileSync(keyPath, 'utf8'));
  } catch {
    throw new Error('Cannot read the Play service-account JSON key');
  }
  if (!key || key.type !== 'service_account' || typeof key.client_email !== 'string' ||
      typeof key.private_key !== 'string' || !key.client_email || !key.private_key) {
    throw new Error('Expected a Google service-account JSON key');
  }
  return key;
}

function assertion(key) {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: 'RS256', typ: 'JWT' };
  if (key.private_key_id) header.kid = key.private_key_id;
  // Ignore token_uri in the file. Assertions only go to Google's fixed OAuth URL.
  const claims = { iss: key.client_email, scope, aud: tokenUrl, iat: now, exp: now + 3600 };
  const signingInput = `${encode(header)}.${encode(claims)}`;
  try {
    const signature = createSign('RSA-SHA256').update(signingInput).end().sign(key.private_key, 'base64url');
    return `${signingInput}.${signature}`;
  } catch {
    throw new Error('Cannot sign the Google service-account assertion');
  }
}

async function jsonRequest(request, url, options, operation, timeout = 60000) {
  let response;
  try {
    response = await request(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(timeout) });
  } catch {
    throw new Error(`Google ${operation} request failed; its outcome may be unknown`);
  }
  // Do not include response bodies or underlying network errors: they can echo tokens.
  if (!response.ok) throw new Error(`Google ${operation} failed (HTTP ${response.status})`);
  try {
    return await response.json();
  } catch {
    throw new Error(`Google ${operation} returned invalid JSON`);
  }
}

export async function uploadPlayInternal(options, request = fetch) {
  const { app, aabPath, version, versionCode, keyPath, releaseNotes } = options;
  const packageName = Object.hasOwn(packages, app) ? packages[app] : undefined;
  if (!packageName) throw new Error('Unknown app for Play Internal release');
  if (options.track !== undefined && options.track !== 'internal') throw new Error('Only the Internal track is allowed');
  releaseIdentity(version, versionCode);
  if (!aabPath?.endsWith('.aab')) throw new Error('Expected an Android App Bundle path');
  let size;
  try {
    const file = statSync(aabPath);
    if (!file.isFile() || file.size === 0) throw new Error();
    size = file.size;
  } catch {
    throw new Error('Cannot read the Android App Bundle');
  }
  if (releaseNotes !== undefined && (!Array.isArray(releaseNotes) || releaseNotes.some((note) =>
    !note || typeof note.language !== 'string' || typeof note.text !== 'string' || !note.language || !note.text))) {
    throw new Error('Release notes must contain language and text');
  }
  const key = serviceAccount(keyPath);
  const token = await jsonRequest(request, tokenUrl, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: assertion(key) }),
  }, 'OAuth token');
  if (typeof token.access_token !== 'string' || !token.access_token) throw new Error('Google OAuth returned no access token');
  const headers = { Authorization: `Bearer ${token.access_token}`, 'Content-Type': 'application/json' };
  const base = `${publisherUrl}/androidpublisher/v3/applications/${packageName}/edits`;
  const edit = await jsonRequest(request, base, { method: 'POST', headers, body: '{}' }, 'Play edit creation');
  if (typeof edit.id !== 'string' || !/^[a-zA-Z0-9_-]+$/.test(edit.id)) throw new Error('Google Play returned an invalid edit ID');
  const editUrl = `${base}/${encodeURIComponent(edit.id)}`;
  const stream = createReadStream(aabPath);
  let bundle;
  try {
    bundle = await jsonRequest(request,
      `${publisherUrl}/upload/androidpublisher/v3/applications/${packageName}/edits/${encodeURIComponent(edit.id)}/bundles?uploadType=media`, {
        method: 'POST', headers: { Authorization: headers.Authorization, 'Content-Type': 'application/octet-stream', 'Content-Length': String(size) },
        body: stream, duplex: 'half',
      }, 'Play bundle upload', 15 * 60000);
  } finally {
    stream.destroy();
  }
  if (bundle.versionCode !== versionCode) throw new Error('Uploaded bundle versionCode does not match the reserved release; track was not changed');
  const release = { name: version, versionCodes: [String(versionCode)], status: 'completed' };
  if (releaseNotes?.length) release.releaseNotes = releaseNotes;
  await jsonRequest(request, `${editUrl}/tracks/internal`, {
    method: 'PUT', headers, body: JSON.stringify({ track: 'internal', releases: [release] }),
  }, 'Play Internal track update');
  // Internal releases publish automatically. Do not set changesNotSentForReview.
  // Keep the explicit guard: Google's default behavior can cancel an existing review.
  await jsonRequest(request, `${editUrl}:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW`, {
    method: 'POST', headers,
  }, 'Play edit commit');
  return { app, package: packageName, track: 'internal', version, versionCode, editId: edit.id };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const root = fileURLToPath(new URL('..', import.meta.url));
    const app = JSON.parse(readFileSync(`${root}/package.json`, 'utf8')).name;
    const [aabPath, version, code] = process.argv.slice(2);
    const result = await uploadPlayInternal({ app, aabPath, version, versionCode: Number(code), keyPath: process.env.PLAY_SERVICE_ACCOUNT_KEY_PATH });
    console.log(JSON.stringify(result));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
