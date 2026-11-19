import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { Window } from 'happy-dom';
import { RECOMMENDED_DEFAULTS } from '../src/features/protection/recommended-defaults.ts';

const assets = new URL('../targets/safari-feeds/assets/', import.meta.url);
const source = readFileSync(new URL('feed-rules.js', assets), 'utf8');
const contentScript = {};
runInNewContext(source, contentScript);
const { installZenModeFeedRules } = contentScript;

const homeUrl = 'https://m.youtube.com/?ra=m';
const shortsUrl = 'https://m.youtube.com/shorts/8yb5NYP5bf8?ra=m';
const settings = RECOMMENDED_DEFAULTS;

function loadPage(t, fixture, url) {
  const window = new Window({ url });
  t.after(() => window.happyDOM.close());
  const html = readFileSync(new URL(`fixtures/safari-feeds/${fixture}.html`, import.meta.url), 'utf8');
  window.document.write(html);
  return window;
}

test('Safari loads the Shorts scripts only on the origin supported by the rules', (t) => {
  const manifest = JSON.parse(readFileSync(new URL('manifest.json', assets), 'utf8'));
  const matches = [];

  for (const url of [homeUrl, 'https://www.youtube.com/', 'https://youtube.com/', 'http://m.youtube.com/', 'https://example.org/']) {
    const { document } = loadPage(t, 'youtube-home', url);
    const tab = document.querySelector('.pivot-shorts').parentElement;
    t.after(installZenModeFeedRules(document, settings));
    if (tab.style.display === 'none') matches.push(`${new URL(url).origin}/*`);
  }

  assert.deepEqual(manifest.content_scripts.flatMap((entry) => entry.matches), matches);

  const { document } = loadPage(t, 'youtube-home', homeUrl);
  const tab = document.querySelector('.pivot-shorts').parentElement;
  const context = { document };
  for (const entry of manifest.content_scripts) {
    for (const filename of entry.js) {
      runInNewContext(readFileSync(new URL(filename, assets), 'utf8'), context);
    }
  }
  assert.equal(tab.style.display, 'none');
});

test('Expo registers the Safari extension for EAS under the app bundle ID', () => {
  const cli = fileURLToPath(import.meta.resolve('expo/bin/cli'));
  const config = JSON.parse(execFileSync(process.execPath, [cli, 'config', '--type', 'prebuild', '--json'], {
    cwd: new URL('..', import.meta.url),
    encoding: 'utf8',
  }));

  const extensions = config.extra.eas.build.experimental.ios.appExtensions;
  assert.ok(extensions.some(({ bundleIdentifier }) => bundleIdentifier === `${config.ios.bundleIdentifier}.safari-feeds`));
});

test('YouTube Home hides the Shorts tab and leaves the other tabs and Home content visible', (t) => {
  const window = loadPage(t, 'youtube-home', homeUrl);
  const { document } = window;
  const tabs = [...document.querySelectorAll('ytm-pivot-bar-item-renderer')];
  assert.deepEqual(tabs.map((tab) => tab.textContent.trim()), ['Home', 'Shorts', 'Subscriptions', 'You']);

  t.after(installZenModeFeedRules(document, settings));

  const hiddenTabs = tabs.filter((tab) => window.getComputedStyle(tab).display === 'none');
  assert.deepEqual(hiddenTabs.map((tab) => tab.textContent.trim()), ['Shorts']);
  assert.equal(tabs[1].isConnected, true);
  assert.notEqual(window.getComputedStyle(document.querySelector('ytm-feed-nudge-renderer')).display, 'none');
});

test('a direct YouTube Shorts visit hides the carousel and keeps the player and top bar in the DOM', (t) => {
  const window = loadPage(t, 'youtube-shorts', shortsUrl);
  const { document } = window;
  const player = document.querySelector('video');
  const carousel = player.closest('shorts-carousel');
  const topBar = document.querySelector('ytm-mobile-topbar-renderer');

  t.after(installZenModeFeedRules(document, settings));

  assert.equal(window.getComputedStyle(carousel).display, 'none');
  assert.equal(player.isConnected, true);
  assert.notEqual(window.getComputedStyle(topBar).display, 'none');
});

for (const [fixture, url] of [['youtube-home', homeUrl], ['youtube-shorts', shortsUrl]]) {
  test(`${fixture} stays unchanged when the stored Shorts setting is off`, (t) => {
    const { document } = loadPage(t, fixture, url);
    const before = document.documentElement.outerHTML;

    t.after(installZenModeFeedRules(document, { ...settings, shortsEnabled: false }));

    assert.equal(document.documentElement.outerHTML, before);
  });
}

for (const [fixture, url] of [
  ['youtube-consent', homeUrl],
  ['instagram-sign-in', 'https://www.instagram.com/'],
  ['x-sign-in', 'https://x.com/'],
]) {
  test(`${fixture} stays unchanged with the stored feed settings on`, (t) => {
    const { document } = loadPage(t, fixture, url);
    const before = document.documentElement.outerHTML;

    t.after(installZenModeFeedRules(document, settings));

    assert.equal(document.documentElement.outerHTML, before);
  });
}

test('duplicate Shorts tabs leave an ambiguous Home layout unchanged', (t) => {
  const { document } = loadPage(t, 'youtube-home', homeUrl);
  const tab = document.querySelector('.pivot-shorts').parentElement;
  tab.after(tab.cloneNode(true));
  const before = document.documentElement.outerHTML;

  t.after(installZenModeFeedRules(document, settings));

  assert.equal(document.documentElement.outerHTML, before);
});

test('the rules follow YouTube SPA changes and restore the old display style', async (t) => {
  const window = loadPage(t, 'youtube-home', homeUrl);
  const shortsWindow = loadPage(t, 'youtube-shorts', shortsUrl);
  const { document } = window;
  const tab = document.querySelector('.pivot-shorts').parentElement;
  tab.style.setProperty('display', 'flex', 'important');
  const formerStyle = tab.getAttribute('style');
  const stop = installZenModeFeedRules(document, settings);
  t.after(stop);
  assert.equal(window.getComputedStyle(tab).display, 'none');

  window.history.pushState(null, '', shortsUrl);
  document.body.replaceWith(document.importNode(shortsWindow.document.body, true));
  await window.happyDOM.waitUntilComplete();

  const carousel = document.querySelector('shorts-carousel');
  assert.equal(window.getComputedStyle(carousel).display, 'none');
  assert.equal(tab.getAttribute('style'), formerStyle);

  window.history.pushState(null, '', '/watch?v=8yb5NYP5bf8');
  document.body.append(document.createElement('div'));
  await window.happyDOM.waitUntilComplete();

  assert.equal(carousel.hasAttribute('style'), false);
  assert.equal(carousel.isConnected, true);

  window.history.pushState(null, '', shortsUrl);
  document.body.append(document.createElement('div'));
  await window.happyDOM.waitUntilComplete();
  assert.equal(window.getComputedStyle(carousel).display, 'none');

  stop();
  assert.equal(carousel.hasAttribute('style'), false);
});
