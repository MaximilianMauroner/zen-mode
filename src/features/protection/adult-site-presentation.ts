import type { ZenGuardStatus } from './native';
import { checkedInstalledBrowserCount, getBrowserReadiness } from './target-availability.ts';

type AdultSitePresentation = {
  detail: string;
  statusLabel: string;
  tone: 'accent' | 'neutral' | 'danger';
};

/** Saved website rules never claim to be running without access and global protection. */
export function getAdultSitePresentation(status: ZenGuardStatus | null, loading = false): AdultSitePresentation {
  if (!status) {
    return { detail: loading ? 'Checking the website rule.' : 'Website status unavailable.', statusLabel: '—', tone: 'neutral' };
  }
  if (!status.available) {
    return { detail: 'Website blocking needs the Android app.', statusLabel: 'ANDROID', tone: 'neutral' };
  }
  if (!status.adultSiteEnabled) {
    return { detail: 'No website rule is running.', statusLabel: 'OFF', tone: 'neutral' };
  }
  if (!status.serviceEnabled) {
    return { detail: 'Rule saved · Android access needed.', statusLabel: 'SAVED', tone: 'danger' };
  }
  if (!status.protectionEnabled) {
    return { detail: 'Rule saved · Protection paused.', statusLabel: 'SAVED', tone: 'danger' };
  }
  const browserReadiness = getBrowserReadiness(status);
  const custom = status.adultSiteCustomCount ? ` · ${status.adultSiteCustomCount} added` : '';
  if (browserReadiness === 'none-installed') {
    return { detail: `On · no supported browser is installed${custom}.`, statusLabel: 'NO BROWSER', tone: 'neutral' };
  }
  if (browserReadiness !== 'ready') {
    const detail = browserReadiness === 'unknown'
      ? 'On · browser availability is unknown. Check a supported browser.'
      : 'On · check an installed supported browser.';
    return { detail: `${detail}${custom}`, statusLabel: 'CHECK', tone: 'neutral' };
  }
  const checkedBrowsers = checkedInstalledBrowserCount(status);
  return {
    detail: `Adult-site blocking in ${checkedBrowsers} checked supported browser${checkedBrowsers === 1 ? '' : 's'}${custom}.`,
    statusLabel: 'ON',
    tone: 'accent',
  };
}
