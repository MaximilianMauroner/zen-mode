import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { uploadPlayInternal } from '../scripts/upload-play-internal.mjs';

function fixture() {
  const directory = mkdtempSync(join(tmpdir(), 'play-uploader-test-'));
  const { privateKey, publicKey } = generateKeyPairSync('rsa', {
    modulusLength: 2048, privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    publicKeyEncoding: { type: 'spki', format: 'pem' },
  });
  const keyPath = join(directory, 'test-service-account.json');
  writeFileSync(keyPath, JSON.stringify({ type: 'service_account', client_email: 'test@example.iam.gserviceaccount.com',
    private_key: privateKey, private_key_id: 'test-key', token_uri: 'https://untrusted.example/token' }));
  const aabPath = join(directory, 'test.aab');
  writeFileSync(aabPath, 'test-bundle-content');
  return { publicKey, options: { app: 'moodinator', aabPath, version: '0.1.6', versionCode: 41, keyPath },
    cleanup: () => rmSync(directory, { recursive: true, force: true }) };
}

function fakeGoogle(uploadedCode) {
  const calls = [];
  const request = async (url, options) => {
    const call = { url, ...options };
    calls.push(call);
    if (url === 'https://oauth2.googleapis.com/token') return Response.json({ access_token: 'test-access-token', token_type: 'Bearer' });
    if (url.endsWith('/edits')) return Response.json({ id: 'edit123' });
    if (url.includes('/bundles?')) {
      const bytes = [];
      for await (const chunk of options.body) bytes.push(chunk);
      call.uploaded = Buffer.concat(bytes).toString();
      return Response.json({ versionCode: uploadedCode });
    }
    if (url.endsWith('/tracks/internal')) return Response.json(JSON.parse(options.body));
    if (url.includes(':commit?')) return Response.json({ id: 'edit123' });
    throw new Error('Unexpected API request');
  };
  return { request, calls };
}

test('an uploaded code mismatch cannot change a track or commit an edit', async () => {
  const sample = fixture();
  try {
    const google = fakeGoogle(40);
    await assert.rejects(uploadPlayInternal(sample.options, google.request), /versionCode does not match/);
    assert.equal(google.calls.length, 3);
    assert.equal(google.calls.some((call) => call.method === 'PUT' || call.url.includes(':commit')), false);
    assert.equal(google.calls[2].uploaded, 'test-bundle-content');
  } finally { sample.cleanup(); }
});

test('only Internal is changed, with one completed patch release and protected review behavior', async () => {
  const sample = fixture();
  try {
    const google = fakeGoogle(41);
    const result = await uploadPlayInternal(sample.options, google.request);
    assert.equal(result.package, 'com.lab4code.moodinator');
    assert.equal(result.track, 'internal');
    const update = google.calls.find((call) => call.method === 'PUT');
    assert.ok(update.url.endsWith('/tracks/internal'));
    assert.deepEqual(JSON.parse(update.body), { track: 'internal', releases: [{ name: '0.1.6', versionCodes: ['41'], status: 'completed' }] });
    const commit = google.calls.find((call) => call.url.includes(':commit'));
    const query = new URL(commit.url).searchParams;
    assert.equal(query.get('changesNotSentForReview'), 'true');
    assert.equal(query.get('changesInReviewBehavior'), 'ERROR_IF_IN_REVIEW');
    assert.equal(google.calls.every((call) => call.redirect === 'error'), true);
    assert.equal(google.calls.some((call) => /production|\/tracks\/(alpha|beta)/.test(call.url)), false);
  } finally { sample.cleanup(); }
});

test('key-file endpoints cannot redirect the signed assertion, and the private key is never transmitted', async () => {
  const sample = fixture();
  try {
    const google = fakeGoogle(41);
    await uploadPlayInternal(sample.options, google.request);
    const tokenCall = google.calls[0];
    assert.equal(tokenCall.url, 'https://oauth2.googleapis.com/token');
    const jwt = tokenCall.body.get('assertion');
    const [header, claims, signature] = jwt.split('.');
    const parsed = JSON.parse(Buffer.from(claims, 'base64url').toString());
    assert.equal(parsed.aud, 'https://oauth2.googleapis.com/token');
    assert.equal(parsed.scope, 'https://www.googleapis.com/auth/androidpublisher');
    assert.equal(verify('RSA-SHA256', Buffer.from(`${header}.${claims}`), sample.publicKey, Buffer.from(signature, 'base64url')), true);
    assert.equal(tokenCall.body.toString().includes('PRIVATE'), false);
    assert.equal(google.calls.every((call) => new URL(call.url).hostname.endsWith('.googleapis.com')), true);
  } finally { sample.cleanup(); }
});

test('unsupported targets fail before authentication and HTTP errors do not echo secrets or retry', async () => {
  const sample = fixture();
  try {
    let requests = 0;
    const request = async () => { requests += 1; return new Response('private-key-or-token-response', { status: 403 }); };
    await assert.rejects(uploadPlayInternal({ ...sample.options, track: 'production' }, request), /Only the Internal/);
    await assert.rejects(uploadPlayInternal({ ...sample.options, app: 'constructor' }, request), /Unknown app/);
    assert.equal(requests, 0);
    await assert.rejects(uploadPlayInternal(sample.options, request), (error) => {
      assert.equal(error.message.includes('HTTP 403'), true);
      assert.equal(error.message.includes('private-key-or-token-response'), false);
      return true;
    });
    assert.equal(requests, 1);
  } finally { sample.cleanup(); }
});
