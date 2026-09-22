import type { ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';
import { getSupportedAppAvailability, type SupportedAppKey } from './target-availability.ts';
import { getXFeedReadiness } from './x-readiness.ts';
import { getYouTubeFeedReadiness } from './youtube-readiness.ts';

type Feed = 'youtubeHome' | 'shorts' | 'reels' | 'home' | 'explore' | 'xHome' | 'xVideos';

/** Describe the effective result separately from a saved rule that cannot run. */
export function getFeedPresentation(status: ZenGuardStatus | null, feed: Feed, loading = false) {
  if (!status) return { statusLabel: loading ? 'Checking' : 'Unavailable', detail: loading ? 'Reading the current status.' : 'The current status could not be read.', tone: 'neutral' as const };
  if (!status.available) return { statusLabel: 'Unavailable', detail: 'Feed protection needs the Android app.', tone: 'neutral' as const };
  if ((feed === 'youtubeHome' && !status.youtubeHomeEnabled) || (feed === 'shorts' && !status.shortsEnabled) || (feed === 'xHome' && !status.xHomeEnabled) || (feed === 'xVideos' && !status.xVideosEnabled)) {
    return { statusLabel: 'Allowed', detail: feed === 'youtubeHome' ? 'The recommendation feed is allowed.' : feed === 'xHome' ? 'No Home-feed breaks are set.' : 'Scrolling has no feed limit.', tone: 'neutral' as const };
  }
  if (feed === 'explore' && !status.instagramExploreBlocked) {
    return { statusLabel: 'Allowed', detail: 'No Explore block is set.', tone: 'neutral' as const };
  }
  const target = feed === 'shorts' || feed === 'youtubeHome' ? 'youtube' : feed === 'xHome' || feed === 'xVideos' ? 'x' : 'instagram';
  const availability = getSupportedAppAvailability(status, target);
  if (availability === 'absent') return unavailableTarget('not installed', target);
  if (availability === 'disabled') return unavailableTarget('disabled in Android', target);
  if (availability === 'unknown') return unavailableTarget('availability could not be confirmed', target);
  if (availability === 'unavailable') return { statusLabel: 'Unavailable', detail: 'Feed protection needs the Android app.', tone: 'neutral' as const };
  if (feed === 'xHome' || feed === 'xVideos') return xPresentation(status, feed);
  if (feed === 'youtubeHome') {
    const saved = 'Block the Home recommendation feed';
    if (!status.youtubeHomeDetectionSupported) return { statusLabel: 'Unavailable', detail: `Saved: ${saved}. This build has no device-verified Home signal, so no Home action will run.`, tone: 'neutral' as const };
    if (!status.serviceEnabled) return { statusLabel: 'Not running', detail: `Saved: ${saved}. Android access is needed.`, tone: 'neutral' as const };
    if (!status.protectionEnabled) return { statusLabel: 'Not running', detail: `Saved: ${saved}. Protection is paused.`, tone: 'neutral' as const };
    if (getYouTubeFeedReadiness(status, 'home') === 'awaiting') return { statusLabel: 'Check', detail: `Saved: ${saved}. Device-verified Home detection is not available yet; no Home action will run.`, tone: 'neutral' as const };
    return { statusLabel: 'Blocked', detail: 'The Home recommendation feed is blocked.', tone: 'accent' as const };
  }
  const saved = feed === 'shorts' ? 'One Short per visit' : feed === 'reels' ? `${status.instagramWaitSeconds}s pause, ${status.instagramReelsMinutes}m viewing window` : feed === 'home' ? `${status.instagramHomeMinutes}m of home-feed viewing` : 'Block Explore';
  const reason = !status.serviceEnabled ? 'Android access is needed.' : !status.protectionEnabled ? 'Protection is paused.' : feed === 'shorts' ? !isShortsReady(status) ? 'Finish feed setup.' : null : status.instagramObservationMode || (status.instagramSignalMask & 3) !== 3 ? 'Finish feed setup.' : null;
  if (reason) return { statusLabel: 'Not running', detail: `Saved: ${saved}. ${reason}`, tone: 'neutral' as const };
  if (feed === 'home' && status.instagramHomeUsageState === 'unknown') {
    return { statusLabel: 'Unavailable', detail: `${saved}. Instagram Home usage could not be verified; wait for Zen Mode to reconnect.`, tone: 'neutral' as const };
  }
  const detail = feed === 'shorts' ? 'Watch one Short. Scrolling to another is blocked.' : feed === 'reels' ? `Wait ${status.instagramWaitSeconds}s, then watch for ${status.instagramReelsMinutes}m.` : feed === 'home' ? `${status.instagramHomeMinutes}m of home-feed viewing.` : 'The Explore grid cannot open.';
  return { statusLabel: feed === 'explore' ? 'Blocked' : 'Limited', detail, tone: 'accent' as const };
}

function unavailableTarget(reason: string, app: SupportedAppKey) {
  const label = app === 'youtube' ? 'YouTube' : app === 'instagram' ? 'Instagram' : 'X';
  return { statusLabel: reason === 'not installed' ? 'Not installed' : reason === 'disabled in Android' ? 'Disabled' : 'Unknown', detail: `Saved rule not active: ${label} is ${reason}.`, tone: 'neutral' as const };
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
  if (feed === 'xHome' && status.xHomeUsageState === 'unknown') {
    return { statusLabel: 'Unavailable', detail: `${saved}. X Home usage could not be verified; leave X and wait for Zen Mode to reconnect.`, tone: neutral };
  }
  return { statusLabel: 'Limited', detail: `${saved}.`, tone: 'accent' as const };
}

function isShortsReady(status: ZenGuardStatus): boolean {
  return !status.observationMode && typeof status.lastDetectionAt === 'number' && status.lastDetectionAt > 0;
}
