import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { hasAwaitingXFeed } from './x-readiness.ts';

export type OverviewAction =
  | 'retry'
  | 'open-accessibility'
  | 'resume-protection'
  | 'check-youtube'
  | 'limit-shorts'
  | 'check-instagram'
  | 'start-instagram'
  | 'set-up-x';

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

  if (status.shortsEnabled && status.observationMode) {
    return status.lastDetectionAt === 0 ? 'check-youtube' : 'limit-shorts';
  }
  if (status.instagramObservationMode) {
    return (status.instagramSignalMask & 3) !== 3 ? 'check-instagram' : 'start-instagram';
  }

  const xEnabled = status.xHomeEnabled || status.xVideosEnabled;
  if (xEnabled && (status.xObservationMode || hasAwaitingXFeed(status))) return 'set-up-x';
  return null;
}
