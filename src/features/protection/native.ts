import ZenGuardModule, { type ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

export type { ZenGuardStatus };

export const getZenGuardStatus = () => ZenGuardModule.getStatus();
export const openAccessibilitySettings = () => ZenGuardModule.openAccessibilitySettings();
export const openYouTube = () => ZenGuardModule.openYouTube();
export const openInstagram = () => ZenGuardModule.openInstagram();
export const setNativeProtectionEnabled = (enabled: boolean) => ZenGuardModule.setProtectionEnabled(enabled);
export const setObservationMode = (enabled: boolean) => ZenGuardModule.setObservationMode(enabled);
export const setInstagramObservationMode = (enabled: boolean) => ZenGuardModule.setInstagramObservationMode(enabled);
export const setInstagramSettings = (
  waitSeconds: number,
  reelsMinutes: number,
  homeMinutes: number,
  exploreBlocked: boolean,
) => ZenGuardModule.setInstagramSettings(waitSeconds, reelsMinutes, homeMinutes, exploreBlocked);
