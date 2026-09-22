import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { getBrowserReadiness, getSupportedAppAvailability, type PackageAvailability } from './target-availability.ts';
import { getXFeedReadiness } from './x-readiness.ts';
import { getYouTubeFeedReadiness } from './youtube-readiness.ts';

/** A global switch does not mean every saved rule is ready to enforce. */
export type FeedStatus =
  | 'unknown'
  | 'unavailable'
  | 'permission'
  | 'paused'
  | 'empty'
  | 'setup'
  | 'partial'
  | 'targets-unavailable'
  | 'targets-unknown'
  | 'active';

export type ProtectionReadiness = {
  enabled: number;
  ready: number;
  pending: number;
  unavailable: number;
  unknown: number;
};

/**
 * Count only rules that exist in the current snapshot. Installed/absent state
 * is separate from rule switches, so an uninstall never changes persistence.
 */
export function getProtectionReadiness(status: ZenGuardStatus): ProtectionReadiness {
  const result: ProtectionReadiness = { enabled: 0, ready: 0, pending: 0, unavailable: 0, unknown: 0 };
  const add = (enabled: boolean, availability: PackageAvailability, ready: boolean) => {
    if (!enabled) return;
    result.enabled += 1;
    if (availability === 'installed') {
      if (ready) result.ready += 1;
      else result.pending += 1;
    } else if (availability === 'absent' || availability === 'disabled' || availability === 'unavailable') {
      result.unavailable += 1;
    } else {
      result.unknown += 1;
    }
  };

  add(status.shortsEnabled, getSupportedAppAvailability(status, 'youtube'), isShortsReady(status));
  if (status.youtubeHomeEnabled) {
    if (!status.youtubeHomeDetectionSupported) {
      result.enabled += 1;
      result.unavailable += 1;
    } else {
      add(true, getSupportedAppAvailability(status, 'youtube'), getYouTubeFeedReadiness(status, 'home') === 'ready');
    }
  }
  add(status.xHomeEnabled, getSupportedAppAvailability(status, 'x'), !status.xObservationMode && getXFeedReadiness(status, 'home') === 'ready');
  add(status.xVideosEnabled, getSupportedAppAvailability(status, 'x'), !status.xObservationMode && getXFeedReadiness(status, 'videos') === 'ready');

  // Instagram has no master switch in the existing UI. Its saved Reels/Home
  // rules remain a configured target even after observation has completed.
  const instagramConfigured = status.instagramObservationMode || status.instagramSignalMask !== 0 || status.instagramExploreBlocked === true;
  add(instagramConfigured, getSupportedAppAvailability(status, 'instagram'), (status.instagramSignalMask & 3) === 3 && !status.instagramObservationMode);

  if (status.adultSiteEnabled) {
    result.enabled += 1;
    switch (getBrowserReadiness(status)) {
      case 'ready': result.ready += 1; break;
      case 'check': result.pending += 1; break;
      case 'none-installed': result.unavailable += 1; break;
      case 'disabled': result.unavailable += 1; break;
      case 'unknown': result.unknown += 1; break;
      case 'unavailable': result.unavailable += 1; break;
    }
  }
  return result;
}

/**
 * Used by both the fixed header and the overview. `active` means active for
 * ready rules, never that every enabled target is ready.
 */
export function getFeedStatus(status: ZenGuardStatus | null): FeedStatus {
  if (!status) return 'unknown';
  if (!status.available) return 'unavailable';
  if (!status.serviceEnabled) return 'permission';
  if (!status.protectionEnabled) return 'paused';

  const readiness = getProtectionReadiness(status);
  if (readiness.enabled === 0) return 'empty';
  if (readiness.ready === 0 && readiness.pending === 0 && readiness.unknown === 0) return 'targets-unavailable';
  if (readiness.ready === 0 && readiness.pending === 0 && readiness.unknown > 0) return 'targets-unknown';
  if (readiness.pending > 0 || readiness.unknown > 0) return readiness.ready > 0 ? 'partial' : 'setup';
  return 'active';
}

export function getFeedStatusDetail(status: ZenGuardStatus | null): string {
  const state = getFeedStatus(status);
  if (state === 'unknown') return 'Checking the current Android protection status.';
  if (state === 'unavailable') return 'This is an interface preview. Android app access is required to enforce rules.';
  if (state === 'permission') return 'Enable Zen Mode in Android Accessibility settings to apply your saved rules.';
  if (state === 'paused') return 'Protection is off. Resume it in Settings when you are ready.';
  if (state === 'empty') return 'No feed or site rules enabled. Open a feed or Sites to choose a boundary, or App limits for whole-app rules.';
  if (state === 'targets-unavailable') return 'Your saved feed/site targets are unavailable. Install or enable one to use its rule; saved settings remain unchanged.';
  if (state === 'targets-unknown') return 'Zen Mode could not confirm a supported feed app or browser. Refresh after checking Android access.';

  const readiness = status ? getProtectionReadiness(status) : null;
  if (state === 'setup') return `Feed/site setup needed: complete the check for ${readiness?.pending ?? 0} enabled rule${readiness?.pending === 1 ? '' : 's'} before it can run.${readiness?.unknown ? ' Availability for another target is still unknown.' : ''}`;
  if (state === 'partial') return 'Feed/site protection is active for ready rules. Complete the remaining app or browser checks below.';
  if (readiness && readiness.unavailable > 0) return 'Feed/site protection is active for ready rules. Some saved targets are unavailable.';
  return 'Feed/site protection is active for the enabled rules that have passed their checks.';
}

function isShortsReady(status: ZenGuardStatus): boolean {
  return !status.observationMode && typeof status.lastDetectionAt === 'number' && status.lastDetectionAt > 0;
}
