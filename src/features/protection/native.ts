import ZenGuardModule, { type ZenGuardStatus } from '../../../modules/zen-guard/src/ZenGuardModule';

export type { ZenGuardStatus };

export const getZenGuardStatus = () => ZenGuardModule.getStatus();
export const openAccessibilitySettings = () => ZenGuardModule.openAccessibilitySettings();
export const openYouTube = () => ZenGuardModule.openYouTube();
export const setNativeProtectionEnabled = (enabled: boolean) => ZenGuardModule.setProtectionEnabled(enabled);
export const setObservationMode = (enabled: boolean) => ZenGuardModule.setObservationMode(enabled);
