import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { getAwaitingXFeeds, type XFeed } from './x-readiness.ts';

export type XDrawerGuidance = {
  /** Enabled feeds still awaiting a signal during first-time observation. */
  initialAwaiting: XFeed[];
  /** Enabled feeds switched on after first-time observation ended. */
  postSetupAwaiting: XFeed[];
};

/**
 * Keep the drawer's initial checklist and later follow-up guidance on the same
 * readiness snapshot. Follow-up guidance is intentionally hidden during the
 * initial observation flow, where the complete checklist is shown instead.
 */
export function getXDrawerGuidance(status: ZenGuardStatus | null, observing: boolean): XDrawerGuidance {
  const awaiting = getAwaitingXFeeds(status);
  return {
    initialAwaiting: awaiting,
    postSetupAwaiting: observing ? [] : awaiting,
  };
}
