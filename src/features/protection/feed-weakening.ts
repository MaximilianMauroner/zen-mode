import type { ZenGuardStatus } from './native';

/** Names the first weaker feed change using the same comparisons as the settings lock. */
export function feedWeakeningAction(stored: ZenGuardStatus, proposed: ZenGuardStatus): string | null {
  if (stored.shortsEnabled && !proposed.shortsEnabled) return 'disable the YouTube Shorts limit';
  if (stored.youtubeHomeEnabled && !proposed.youtubeHomeEnabled) return 'disable YouTube Home blocking';
  if (stored.xHomeEnabled && !proposed.xHomeEnabled) return 'disable X Home breaks';
  if (stored.xVideosEnabled && !proposed.xVideosEnabled) return 'disable the X video limit';
  if (proposed.xHomeMinutes > stored.xHomeMinutes) return `increase the X Home interval to ${proposed.xHomeMinutes} minutes`;
  if (proposed.instagramWaitSeconds < stored.instagramWaitSeconds) return `shorten the Instagram Reels pause to ${proposed.instagramWaitSeconds} seconds`;
  if (proposed.instagramReelsMinutes > stored.instagramReelsMinutes) return `increase the Instagram Reels window to ${proposed.instagramReelsMinutes} minutes`;
  if (proposed.instagramHomeMinutes > stored.instagramHomeMinutes) return `increase the Instagram Home allowance to ${proposed.instagramHomeMinutes} minutes`;
  if (stored.instagramExploreBlocked && !proposed.instagramExploreBlocked) return 'disable Instagram Explore blocking';
  return null;
}
