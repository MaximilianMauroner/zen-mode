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
