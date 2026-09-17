import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { hasAwaitingXFeed } from './x-readiness.ts';

/** A service switch alone does not mean either feed detector is enforcing. */
export function getFeedStatus(status: ZenGuardStatus | null) {
  if (!status) return 'unknown';
  if (!status.available) return 'unavailable';
  if (!status.serviceEnabled) return 'permission';
  if (!status.protectionEnabled) return 'paused';
  // An X feed switched on after setup enforces nothing until its own surface is
  // seen, so the summary must not claim full protection while one is waiting.
  if ((status.shortsEnabled && status.observationMode) || status.instagramObservationMode || ((status.xHomeEnabled || status.xVideosEnabled) && status.xObservationMode) || hasAwaitingXFeed(status)) return 'setup';
  return 'active';
}
