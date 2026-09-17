import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

/** Signal bits the accessibility service sets once it has seen each X surface. */
export const X_HOME_SIGNAL = 1;
export const X_VIDEO_SIGNAL = 2;

export type XFeed = 'home' | 'videos';

export type XFeedReadiness =
  /** The rule is switched off. */
  | 'off'
  /** Switched on, but the service has not seen this surface yet, so it enforces nothing. */
  | 'awaiting'
  /** Observed, so the rule runs as soon as access, protection, and setup allow. */
  | 'ready';

/**
 * Whether one X feed can be enforced.
 *
 * Each feed waits for its own observed signal, so switching a second feed on
 * never pauses the first. `ready` means the rule is able to run; whether it is
 * actually running also depends on Android access, protection, and first-time
 * setup.
 */
export function getXFeedReadiness(status: ZenGuardStatus | null, feed: XFeed): XFeedReadiness {
  if (!status) return 'off';
  const enabled = feed === 'home' ? status.xHomeEnabled : status.xVideosEnabled;
  if (!enabled) return 'off';
  const bit = feed === 'home' ? X_HOME_SIGNAL : X_VIDEO_SIGNAL;
  return (status.xSignalMask & bit) === bit ? 'ready' : 'awaiting';
}

/** Enabled X feeds that have not yet produced the signal they enforce on. */
export function getAwaitingXFeeds(status: ZenGuardStatus | null): XFeed[] {
  return (['home', 'videos'] as const).filter((feed) => getXFeedReadiness(status, feed) === 'awaiting');
}

/** Whether any enabled X feed still needs its own observed surface. */
export function hasAwaitingXFeed(status: ZenGuardStatus | null): boolean {
  return getAwaitingXFeeds(status).length > 0;
}

/** The signals first-time setup waits for, given which X feeds are switched on. */
export function requiredXSignals(status: ZenGuardStatus | null): number {
  if (!status) return 0;
  return (status.xHomeEnabled ? X_HOME_SIGNAL : 0) | (status.xVideosEnabled ? X_VIDEO_SIGNAL : 0);
}

/** True once every switched-on X feed has been seen, which is what starts protection. */
export function hasAllRequiredXSignals(status: ZenGuardStatus | null): boolean {
  if (!status) return false;
  const required = requiredXSignals(status);
  return (status.xSignalMask & required) === required;
}
