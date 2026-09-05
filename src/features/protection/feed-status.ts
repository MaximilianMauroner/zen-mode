import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

/** A service switch alone does not mean either feed detector is enforcing. */
export function getFeedStatus(status: ZenGuardStatus | null) {
  if (!status) return 'unknown';
  if (!status.available) return 'unavailable';
  if (!status.serviceEnabled) return 'permission';
  if (!status.protectionEnabled) return 'paused';
  if ((status.shortsEnabled && status.observationMode) || status.instagramObservationMode || ((status.xHomeEnabled || status.xVideosEnabled) && status.xObservationMode)) return 'setup';
  return 'active';
}
