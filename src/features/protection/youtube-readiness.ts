import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

export type YouTubeFeed = 'home' | 'shorts';

export function getYouTubeFeedReadiness(status: ZenGuardStatus, feed: YouTubeFeed): 'disabled' | 'awaiting' | 'ready' {
  if (feed === 'home') {
    if (!status.youtubeHomeEnabled) return 'disabled';
    return status.youtubeHomeObserved ? 'ready' : 'awaiting';
  }
  if (!status.shortsEnabled) return 'disabled';
  return status.lastDetectionAt > 0 && !status.observationMode ? 'ready' : 'awaiting';
}

export function getYouTubeDrawerGuidance(status: ZenGuardStatus | null) {
  if (!status) return [] as YouTubeFeed[];
  return (['home', 'shorts'] as const).filter((feed) => getYouTubeFeedReadiness(status, feed) === 'awaiting');
}

/** Initial setup can end only after every enabled, supported YouTube rule has its own signal. */
export function hasAllRequiredYouTubeSignals(status: ZenGuardStatus): boolean {
  const hasEnabledFeed = status.shortsEnabled || status.youtubeHomeEnabled;
  const shortsReady = !status.shortsEnabled || status.lastDetectionAt > 0;
  const homeReady = !status.youtubeHomeEnabled ||
    status.youtubeHomeDetectionSupported && status.youtubeHomeObserved;
  return hasEnabledFeed && shortsReady && homeReady;
}
