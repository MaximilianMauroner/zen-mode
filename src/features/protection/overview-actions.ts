import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { getBrowserReadiness, getSupportedAppAvailability } from './target-availability.ts';
import { hasAwaitingXFeed } from './x-readiness.ts';

export type OverviewAction =
  | 'retry'
  | 'open-accessibility'
  | 'resume-protection'
  | 'check-youtube'
  | 'limit-shorts'
  | 'check-instagram'
  | 'start-instagram'
  | 'set-up-x'
  | 'check-sites';

/**
 * Selects the overview's highest-priority action from one native snapshot.
 * X setup includes a feed enabled after initial setup but still awaiting its
 * own signal; opening the drawer is guidance only and does not change state.
 */
export function getOverviewAction(status: ZenGuardStatus | null, hasReadError: boolean): OverviewAction | null {
  if (hasReadError) return 'retry';
  if (!status?.available) return null;
  if (!status.serviceEnabled) return 'open-accessibility';
  if (!status.protectionEnabled) return 'resume-protection';

  if ((status.shortsEnabled || status.youtubeHomeEnabled) && getSupportedAppAvailability(status, 'youtube') === 'installed') {
    if (status.youtubeHomeEnabled && status.youtubeHomeDetectionSupported && !status.youtubeHomeObserved) return 'check-youtube';
    if (status.shortsEnabled && !isShortsReady(status)) return status.lastDetectionAt === 0 ? 'check-youtube' : 'limit-shorts';
  }
  const instagramConfigured = status.instagramObservationMode || status.instagramSignalMask !== 0 || status.instagramExploreBlocked === true;
  if (instagramConfigured && getSupportedAppAvailability(status, 'instagram') === 'installed') {
    if (status.instagramObservationMode) return (status.instagramSignalMask & 3) !== 3 ? 'check-instagram' : 'start-instagram';
    if ((status.instagramSignalMask & 3) !== 3) return 'check-instagram';
  }

  const xEnabled = status.xHomeEnabled || status.xVideosEnabled;
  const xAvailability = getSupportedAppAvailability(status, 'x');
  if (xEnabled && xAvailability === 'installed' && (status.xObservationMode || hasAwaitingXFeed(status))) return 'set-up-x';
  if (status.adultSiteEnabled && ['check', 'unknown'].includes(getBrowserReadiness(status))) return 'check-sites';
  return null;
}

function isShortsReady(status: ZenGuardStatus): boolean {
  return !status.observationMode && typeof status.lastDetectionAt === 'number' && status.lastDetectionAt > 0;
}
