import assert from 'node:assert/strict';
import { afterEach, beforeEach, describe, it, mock } from 'node:test';

const MODULE = new URL('../src/features/feedback/reports.ts', import.meta.url);

delete process.env.EXPO_PUBLIC_REPORTS_ORIGIN;
const unconfigured = await import(`${MODULE}?no-origin`);
process.env.EXPO_PUBLIC_REPORTS_ORIGIN = 'https://reports.test';
const { sendReport } = await import(`${MODULE}?origin`);

const constants = {
  expoConfig: { version: '0.1.5', android: { versionCode: 8, package: 'com.lab4code.zenmode' }, ios: { buildNumber: '3' }, extra: { eas: { projectId: 'project-id' } } },
  installationId: 'installation-id',
  sessionId: 'session-id',
  deviceName: 'Max phone',
};
const android = { OS: 'android', Version: 35, constants: { Release: '15', Serial: 'device-serial', Fingerprint: 'google/device/fingerprint', Model: 'Pixel 9' } };
const device = (platform = android) => ({ constants, platform });
const draft = (change = {}) => ({ kind: 'bug', message: '  Shorts still open after the limit.  ', details: '', contactEmail: '', ...change });
const reply = (status, body) => new Response(body === undefined ? null : JSON.stringify(body), { status });

let fetchMock;
const request = () => {
  assert.equal(fetchMock.mock.callCount(), 1);
  const [url, init] = fetchMock.mock.calls[0].arguments;
  return { url, init, body: JSON.parse(init.body) };
};
const answer = (response) => fetchMock.mock.mockImplementation(async () => response);

beforeEach(() => {
  fetchMock = mock.fn(async () => reply(201, { ok: true }));
  globalThis.fetch = fetchMock;
});
afterEach(() => {
  mock.timers.reset();
  delete globalThis.fetch;
});

describe('report request', () => {
  it('has no sender when the build has no reports origin', () => {
    assert.equal(unconfigured.sendReport, null);
  });

  it('posts JSON without credentials to the configured origin', async () => {
    assert.deepEqual(await sendReport(draft(), device()), { kind: 'sent' });
    const { url, init } = request();
    assert.equal(url, 'https://reports.test/api/reports');
    assert.equal(init.method, 'POST');
    assert.deepEqual(init.headers, { 'Content-Type': 'application/json' });
    assert.equal(init.credentials, undefined);
  });

  it('sends only the typed text, the app version and build, and the OS version', async () => {
    await sendReport(draft({ kind: 'feature', message: ' Limit LinkedIn videos. ', details: '\n I lose time there. ', contactEmail: ' me@example.com ' }), device());
    assert.deepEqual(request().body, {
      app: 'zen-mode',
      kind: 'feature',
      message: 'Limit LinkedIn videos.',
      details: 'I lose time there.',
      page: '/report',
      context: 'Zen Mode 0.1.5 (8) · android 15',
      contactEmail: 'me@example.com',
    });
  });

  it('leaves out every device identifier the Expo objects expose', async () => {
    await sendReport(draft(), device());
    const sent = request().init.body;
    for (const secret of ['device-serial', 'fingerprint', 'Pixel 9', 'installation-id', 'session-id', 'Max phone', 'project-id', 'com.lab4code.zenmode']) {
      assert.equal(sent.includes(secret), false, `${secret} is not sent`);
    }
  });

  it('names the web preview without a version number', async () => {
    await sendReport(draft(), device({ OS: 'web', Version: '', constants: { reactNativeVersion: {} } }));
    assert.equal(request().body.context, 'Zen Mode 0.1.5 · web');
  });

  it('omits empty optional fields', async () => {
    await sendReport(draft({ details: '   ', contactEmail: ' ' }), device());
    assert.deepEqual(Object.keys(request().body).sort(), ['app', 'context', 'kind', 'message', 'page']);
  });
});

describe('report outcome', () => {
  it('reports a network failure as offline', async () => {
    fetchMock.mock.mockImplementation(async () => { throw new TypeError('Network request failed'); });
    assert.deepEqual(await sendReport(draft(), device()), { kind: 'offline' });
  });

  it('reports the rate limit', async () => {
    answer(reply(429, { error: 'rate_limited' }));
    assert.deepEqual(await sendReport(draft(), device()), { kind: 'rate-limited' });
  });

  for (const reason of ['unknown_app', 'invalid_kind', 'empty_message', 'message_too_long', 'details_too_long', 'invalid_email']) {
    it(`keeps the ${reason} code and never the server text`, async () => {
      answer(reply(400, { error: 'invalid_request', reason, message: 'Server words' }));
      assert.deepEqual(await sendReport(draft(), device()), { kind: 'rejected', reason });
    });
  }

  for (const [name, response] of [
    ['a reason this version does not know', reply(400, { error: 'invalid_request', reason: 'something_new' })],
    ['a body that is not JSON', new Response('<html>', { status: 400 })],
  ]) {
    it(`reports a rejection with ${name} as unknown`, async () => {
      answer(response);
      assert.deepEqual(await sendReport(draft(), device()), { kind: 'rejected', reason: 'unknown' });
    });
  }

  for (const [name, response] of [['a wrong content type', reply(415)], ['a server error', reply(503)]]) {
    it(`reports ${name} as a failure`, async () => {
      answer(response);
      assert.deepEqual(await sendReport(draft(), device()), { kind: 'failed' });
    });
  }

  for (const [name, change, reason] of [
    ['an empty message', { message: '  \n ' }, 'empty_message'],
    ['a message over 4000 characters', { message: 'x'.repeat(4001) }, 'message_too_long'],
    ['details over 4000 characters', { details: 'x'.repeat(4001) }, 'details_too_long'],
    ['an invalid email', { contactEmail: 'not-an-email' }, 'invalid_email'],
    ['an email over 254 characters', { contactEmail: `${'a'.repeat(250)}@example.com` }, 'invalid_email'],
  ]) {
    it(`refuses ${name} on the device without sending`, async () => {
      assert.deepEqual(await sendReport(draft(change), device()), { kind: 'rejected', reason });
      assert.equal(fetchMock.mock.callCount(), 0);
    });
  }

  it('accepts text at exactly the limit once trimmed', async () => {
    await sendReport(draft({ message: ` ${'x'.repeat(4000)} `, details: 'y'.repeat(4000) }), device());
    assert.equal(request().body.message.length, 4000);
  });

  it('stops waiting after 15 seconds and says so', async () => {
    mock.timers.enable({ apis: ['setTimeout'] });
    fetchMock.mock.mockImplementation((_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    }));
    let settled = false;
    const pending = sendReport(draft(), device()).finally(() => { settled = true; });
    mock.timers.tick(14_999);
    await Promise.resolve();
    assert.equal(settled, false, 'still waiting before 15 seconds');
    mock.timers.tick(1);
    assert.deepEqual(await pending, { kind: 'timeout' });
  });
});
