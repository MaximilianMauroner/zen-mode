import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { getXFeedReadiness } from './x-readiness.ts';

type Feed = 'shorts' | 'reels' | 'home' | 'explore' | 'xHome' | 'xVideos';

/** Describe the effective result separately from a saved rule that cannot run. */
export function getFeedPresentation(status: ZenGuardStatus | null, feed: Feed, loading = false) {
  if (!status) return { statusLabel: loading ? 'Checking' : 'Unavailable', detail: loading ? 'Reading the current status.' : 'The current status could not be read.', tone: 'neutral' as const };
  if (!status.available) return { statusLabel: 'Unavailable', detail: 'Feed protection needs the Android app.', tone: 'neutral' as const };
  if ((feed === 'shorts' && !status.shortsEnabled) || (feed === 'xHome' && !status.xHomeEnabled) || (feed === 'xVideos' && !status.xVideosEnabled)) {
    return { statusLabel: 'Allowed', detail: feed === 'xHome' ? 'No Home-feed breaks are set.' : 'Scrolling has no feed limit.', tone: 'neutral' as const };
  }
  if (feed === 'xHome' || feed === 'xVideos') return xPresentation(status, feed);
  const saved = feed === 'shorts' ? 'One Short per visit' : feed === 'reels' ? `${status.instagramWaitSeconds}s pause, ${status.instagramReelsMinutes}m viewing window` : feed === 'home' ? `${status.instagramHomeMinutes}m of home-feed viewing` : 'Block Explore';
  if (feed === 'explore' && !status.instagramExploreBlocked) {
    return { statusLabel: 'Allowed', detail: 'No Explore block is set.', tone: 'neutral' as const };
  }
  const reason = !status.serviceEnabled ? 'Android access is needed.' : !status.protectionEnabled ? 'Protection is paused.' : (feed === 'shorts' ? status.observationMode : status.instagramObservationMode) ? 'Finish feed setup.' : null;
  if (reason) return { statusLabel: 'Not running', detail: `Saved: ${saved}. ${reason}`, tone: 'neutral' as const };
  const detail = feed === 'shorts' ? 'Watch one Short. Scrolling to another is blocked.' : feed === 'reels' ? `Wait ${status.instagramWaitSeconds}s, then watch for ${status.instagramReelsMinutes}m.` : feed === 'home' ? `${status.instagramHomeMinutes}m of home-feed viewing.` : 'The Explore grid cannot open.';
  return { statusLabel: feed === 'explore' ? 'Blocked' : 'Limited', detail, tone: 'accent' as const };
}

/**
 * X reports per feed. Each one waits for its own observed signal, so a feed
 * switched on after setup says what it is still waiting for instead of
 * claiming the whole guard is back in setup.
 */
function xPresentation(status: ZenGuardStatus, feed: 'xHome' | 'xVideos') {
  const saved = feed === 'xHome' ? `Break after ${status.xHomeMinutes}m` : 'One video per visit';
  const neutral = 'neutral' as const;

  if (!status.serviceEnabled) return { statusLabel: 'Not running', detail: `Saved: ${saved}. Android access is needed.`, tone: neutral };
  if (!status.protectionEnabled) return { statusLabel: 'Not running', detail: `Saved: ${saved}. Protection is paused.`, tone: neutral };
  if (status.xObservationMode) return { statusLabel: 'Set up', detail: `Saved: ${saved}. Finish X setup.`, tone: neutral };

  if (getXFeedReadiness(status, feed === 'xHome' ? 'home' : 'videos') === 'awaiting') {
    const surface = feed === 'xHome' ? 'the Home feed' : 'one video';
    return { statusLabel: 'Check', detail: `Saved: ${saved}. Open ${surface} in X once to start.`, tone: neutral };
  }
  return { statusLabel: 'Limited', detail: `${saved}.`, tone: 'accent' as const };
}
