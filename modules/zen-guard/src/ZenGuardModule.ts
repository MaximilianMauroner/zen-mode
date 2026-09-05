import { NativeModule, requireNativeModule } from 'expo';

export type ZenGuardStatus = {
  available: boolean;
  serviceEnabled: boolean;
  protectionEnabled: boolean;
  observationMode: boolean;
  shortsEnabled: boolean;
  xHomeEnabled: boolean;
  xVideosEnabled: boolean;
  xHomeMinutes: number;
  xObservationMode: boolean;
  xSignalMask: number;
  lastEventAt: number;
  lastDetectionAt: number;
  detectionCount: number;
  lastDetectionReason: string;
  instagramObservationMode: boolean;
  instagramWaitSeconds: number;
  instagramReelsMinutes: number;
  instagramHomeMinutes: number;
  instagramExploreBlocked: boolean;
  instagramLastDetectionAt: number;
  instagramDetectionCount: number;
  instagramSignalMask: number;
  instagramLastDetectionReason: string;
};

/** A launchable app on this device. */
export type InstalledApp = {
  packageName: string;
  label: string;
};

/** An app with a daily budget, plus how much of today it has spent. */
export type AppLimit = InstalledApp & {
  minutes: number;
  usedMs: number;
};

/** An app that asks first, with a fixed visit length and downtime between visits. */
export type IntentApp = InstalledApp & {
  sessionMinutes: number;
  cooldownMinutes: number;
};

/** An app with a rolling allowance, plus what it spent inside its window. */
export type RollingLimit = InstalledApp & {
  allowanceMinutes: number;
  windowMinutes: number;
  usedMs: number;
};

declare class ZenGuardModule extends NativeModule<{}> {
  getStatus(): Promise<ZenGuardStatus>;
  openAccessibilitySettings(): Promise<void>;
  openYouTube(): Promise<void>;
  openInstagram(): Promise<void>;
  openX(): Promise<void>;
  setShortsEnabled(enabled: boolean): Promise<void>;
  setXSettings(homeEnabled: boolean, videosEnabled: boolean, homeMinutes: number): Promise<void>;
  setXObservationMode(enabled: boolean): Promise<void>;
  setProtectionEnabled(enabled: boolean): Promise<void>;
  setObservationMode(enabled: boolean): Promise<void>;
  setInstagramObservationMode(enabled: boolean): Promise<void>;
  setInstagramSettings(waitSeconds: number, reelsMinutes: number, homeMinutes: number, exploreBlocked: boolean): Promise<void>;
  getInstalledApps(): Promise<InstalledApp[]>;
  getAppLimits(): Promise<AppLimit[]>;
  setAppLimit(packageName: string, minutes: number): Promise<void>;
  removeAppLimit(packageName: string): Promise<void>;
  getIntentApps(): Promise<IntentApp[]>;
  setIntentApp(packageName: string, sessionMinutes: number, cooldownMinutes: number): Promise<void>;
  removeIntentApp(packageName: string): Promise<void>;
  getRollingLimits(): Promise<RollingLimit[]>;
  setRollingLimit(packageName: string, allowanceMinutes: number, windowMinutes: number): Promise<void>;
  removeRollingLimit(packageName: string): Promise<void>;
}

export default requireNativeModule<ZenGuardModule>('ZenGuard');
