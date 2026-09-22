/**
 * One-tap first-install rules. These mirror the native defaults so a fresh
 * install gets the same result whether it comes from storage defaults or
 * from quick setup. Observation stays on: enforcement still needs one
 * observed signal per feed (see the Feeds tab checklist).
 */
export const RECOMMENDED_DEFAULTS = {
  shortsEnabled: true,
  // Home stays off until a positive accessibility signal and safe action are proven on-device.
  youtubeHomeEnabled: false,
  xHomeEnabled: true,
  xVideosEnabled: true,
  xHomeMinutes: 5,
  instagramWaitSeconds: 30,
  instagramReelsMinutes: 5,
  instagramHomeMinutes: 5,
  instagramExploreBlocked: true,
  adultSiteBlockingEnabled: true,
} as const;

export const RECOMMENDED_DEFAULTS_SUMMARY: readonly string[] = [
  'YouTube: one Short per visit',
  'Instagram: 30s pause, 5m Reels, 5m Home, Explore blocked',
  'X: breaks after 5m Home, one video per visit',
  'Sites: block adult sites (built-in list)',
] as const;

/**
 * Apply the recommended rules and start protection in observation mode.
 * Call after acceptSetupConsent(): enabling protection needs current consent.
 * Native access is imported lazily so unit tests can check the constants
 * without loading the Expo native module.
 */
export async function applyRecommendedDefaults(): Promise<void> {
  const native = await import('./native');
  await native.setYouTubeSettings(RECOMMENDED_DEFAULTS.shortsEnabled, RECOMMENDED_DEFAULTS.youtubeHomeEnabled);
  await native.setXSettings(
    RECOMMENDED_DEFAULTS.xHomeEnabled,
    RECOMMENDED_DEFAULTS.xVideosEnabled,
    RECOMMENDED_DEFAULTS.xHomeMinutes,
  );
  await native.setXObservationMode(true);
  await native.setInstagramSettings(
    RECOMMENDED_DEFAULTS.instagramWaitSeconds,
    RECOMMENDED_DEFAULTS.instagramReelsMinutes,
    RECOMMENDED_DEFAULTS.instagramHomeMinutes,
    RECOMMENDED_DEFAULTS.instagramExploreBlocked,
  );
  await native.setInstagramObservationMode(true);
  await native.setObservationMode(true);
  await native.setAdultSiteBlockingEnabled(RECOMMENDED_DEFAULTS.adultSiteBlockingEnabled);
  await native.setNativeProtectionEnabled(true);
}
