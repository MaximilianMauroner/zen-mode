import ZenGuardModule, { type AdultSiteSettings, type AppLimit, type EnforcementStats, type InstalledApp, type IntentApp, type RollingLimit, type ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

export type { AdultSiteSettings, AppLimit, EnforcementStats, InstalledApp, IntentApp, RollingLimit, ZenGuardStatus };

export const getZenGuardStatus = () => ZenGuardModule.getStatus();
export const getEnforcementStats = () => ZenGuardModule.getEnforcementStats();
export const openAccessibilitySettings = () => ZenGuardModule.openAccessibilitySettings();
export const openYouTube = () => ZenGuardModule.openYouTube();
export const openInstagram = () => ZenGuardModule.openInstagram();
export const hasCurrentNativeConsent = () => ZenGuardModule.hasCurrentConsent();
export const acceptCurrentNativeConsent = () => ZenGuardModule.acceptCurrentConsent();
export const setNativeProtectionEnabled = (enabled: boolean) => ZenGuardModule.setProtectionEnabled(enabled);
export const setObservationMode = (enabled: boolean) => ZenGuardModule.setObservationMode(enabled);
export const setInstagramObservationMode = (enabled: boolean) => ZenGuardModule.setInstagramObservationMode(enabled);
export const setInstagramSettings = (
  waitSeconds: number,
  reelsMinutes: number,
  homeMinutes: number,
  exploreBlocked: boolean,
) => ZenGuardModule.setInstagramSettings(waitSeconds, reelsMinutes, homeMinutes, exploreBlocked);

export const getInstalledApps = () => ZenGuardModule.getInstalledApps();
export const getAppLimits = () => ZenGuardModule.getAppLimits();
export const setAppLimit = (packageName: string, minutes: number) => ZenGuardModule.setAppLimit(packageName, minutes);
export const removeAppLimit = (packageName: string) => ZenGuardModule.removeAppLimit(packageName);
export const getIntentApps = () => ZenGuardModule.getIntentApps();
export const setIntentApp = (packageName: string, sessionMinutes: number, cooldownMinutes: number) =>
  ZenGuardModule.setIntentApp(packageName, sessionMinutes, cooldownMinutes);
export const removeIntentApp = (packageName: string) => ZenGuardModule.removeIntentApp(packageName);
export const getRollingLimits = () => ZenGuardModule.getRollingLimits();
export const setRollingLimit = (packageName: string, allowanceMinutes: number, windowMinutes: number) =>
  ZenGuardModule.setRollingLimit(packageName, allowanceMinutes, windowMinutes);
export const removeRollingLimit = (packageName: string) => ZenGuardModule.removeRollingLimit(packageName);

export const openX = () => ZenGuardModule.openX();
export const setYouTubeSettings = (shortsEnabled: boolean, homeEnabled: boolean) => ZenGuardModule.setYouTubeSettings(shortsEnabled, homeEnabled);
export const setXSettings = (homeEnabled: boolean, videosEnabled: boolean, homeMinutes: number) => ZenGuardModule.setXSettings(homeEnabled, videosEnabled, homeMinutes);
export const setXObservationMode = (enabled: boolean) => ZenGuardModule.setXObservationMode(enabled);
export const getAdultSiteSettings = () => ZenGuardModule.getAdultSiteSettings();
export const setAdultSiteBlockingEnabled = (enabled: boolean) => ZenGuardModule.setAdultSiteBlockingEnabled(enabled);
export const addBlockedDomain = (input: string) => ZenGuardModule.addBlockedDomain(input);
export const removeBlockedDomain = (host: string) => ZenGuardModule.removeBlockedDomain(host);
export const openBrowserCheck = () => ZenGuardModule.openBrowserCheck();
