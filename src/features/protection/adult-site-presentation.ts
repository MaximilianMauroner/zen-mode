import type { ZenGuardStatus } from './native';

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
  const checkedBrowsers = countBits(status.browserSignalMask & 15);
  const custom = status.adultSiteCustomCount ? ` · ${status.adultSiteCustomCount} added` : '';
  if (!checkedBrowsers) {
    return { detail: `On · check a browser${custom}.`, statusLabel: 'CHECK', tone: 'neutral' };
  }
  return {
    detail: `Blocking in ${checkedBrowsers} checked browser${checkedBrowsers === 1 ? '' : 's'}${custom}.`,
    statusLabel: 'ON',
    tone: 'accent',
  };
}

function countBits(value: number): number {
  let remaining = value;
  let count = 0;
  while (remaining) {
    count += remaining & 1;
    remaining >>>= 1;
  }
  return count;
}
