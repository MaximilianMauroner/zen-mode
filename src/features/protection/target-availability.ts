import type { PackageAvailability, ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
export type { PackageAvailability };

/** The only app packages whose feed support is implemented by the service. */
export const SUPPORTED_APPS = [
  { key: 'youtube', label: 'YouTube' },
  { key: 'instagram', label: 'Instagram' },
  { key: 'x', label: 'X' },
] as const;

/** The only browser packages whose address bars have a conservative adapter. */
export const SUPPORTED_BROWSERS = [
  { key: 'chrome', label: 'Chrome', mask: 1 },
  { key: 'samsungInternet', label: 'Samsung Internet', mask: 2 },
  { key: 'opera', label: 'Opera', mask: 4 },
  { key: 'firefox', label: 'Firefox', mask: 8 },
] as const;

export type SupportedAppKey = (typeof SUPPORTED_APPS)[number]['key'];
export type SupportedBrowserKey = (typeof SUPPORTED_BROWSERS)[number]['key'];

/**
 * `unknown` is intentionally different from `absent`: an older native module,
 * a failed package-manager read, or a missing status field cannot prove that a
 * package is not installed. `unavailable` means the Android native module is
 * not present at all (for example, the web preview).
 */
const KNOWN_AVAILABILITY = new Set<PackageAvailability>(['installed', 'disabled', 'absent', 'unknown', 'unavailable']);

export function getSupportedAppAvailability(status: ZenGuardStatus | null, app: SupportedAppKey): PackageAvailability {
  if (!status) return 'unknown';
  if (!status.available) return 'unavailable';
  const value = status.appAvailability?.[app];
  return isPackageAvailability(value) ? value : 'unknown';
}

export function getSupportedBrowserAvailability(status: ZenGuardStatus | null, browser: SupportedBrowserKey): PackageAvailability {
  if (!status) return 'unknown';
  if (!status.available) return 'unavailable';
  const value = status.browserAvailability?.[browser];
  return isPackageAvailability(value) ? value : 'unknown';
}

/** The signal bits for browsers that are currently installed and enabled. */
export function installedBrowserMask(status: ZenGuardStatus | null): number | null {
  if (!status || !status.available || !status.browserAvailability) return null;
  return SUPPORTED_BROWSERS.reduce(
    (mask, browser) => getSupportedBrowserAvailability(status, browser.key) === 'installed' ? mask | browser.mask : mask,
    0,
  );
}

export type BrowserReadiness = 'ready' | 'check' | 'none-installed' | 'disabled' | 'unknown' | 'unavailable';

/**
 * Website blocking is ready when one currently installed supported browser has
 * exposed its address bar. A bit for an uninstalled browser is not sufficient.
 */
export function getBrowserReadiness(status: ZenGuardStatus | null): BrowserReadiness {
  if (!status || !status.available) return 'unavailable';
  const installedMask = installedBrowserMask(status);
  if (installedMask === null) return 'unknown';
  const browserStates = SUPPORTED_BROWSERS.map((browser) => getSupportedBrowserAvailability(status, browser.key));
  if (browserStates.every((state) => state === 'unavailable')) return 'unavailable';
  if (installedMask === 0) {
    const hasUnknown = browserStates.some((state) => state === 'unknown' || state === 'unavailable');
    if (hasUnknown) return 'unknown';
    if (browserStates.includes('disabled')) return 'disabled';
    return 'none-installed';
  }
  return typeof status.browserSignalMask === 'number' && (status.browserSignalMask & installedMask) !== 0
    ? 'ready'
    : 'check';
}

/** A count based only on current installed-browser evidence. */
export function checkedInstalledBrowserCount(status: ZenGuardStatus | null): number {
  const installedMask = installedBrowserMask(status);
  if (installedMask === null || typeof status?.browserSignalMask !== 'number') return 0;
  return countBits(status.browserSignalMask & installedMask);
}

function isPackageAvailability(value: unknown): value is PackageAvailability {
  return typeof value === 'string' && KNOWN_AVAILABILITY.has(value as PackageAvailability);
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
