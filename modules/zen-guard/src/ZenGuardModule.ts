import { NativeModule, requireNativeModule } from 'expo';

export type PackageAvailability = 'installed' | 'disabled' | 'absent' | 'unknown' | 'unavailable';
export type SupportedAppAvailability = Record<'youtube' | 'instagram' | 'x', PackageAvailability>;
export type SupportedBrowserAvailability = Record<'chrome' | 'samsungInternet' | 'opera' | 'firefox', PackageAvailability>;

export type ZenGuardStatus = {
  available: boolean;
  serviceEnabled: boolean;
  currentConsent: boolean;
  protectionEnabled: boolean;
  observationMode: boolean;
  shortsEnabled: boolean;
  xHomeEnabled: boolean;
  xVideosEnabled: boolean;
  xHomeMinutes: number;
  xHomeUsedMs: number;
  xHomeBreakRemainingMs: number;
  xHomeUsageState: 'active' | 'paused' | 'unknown';
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
  instagramHomeUsedMs: number;
  instagramHomeBreakRemainingMs: number;
  /** Missing on older native builds; unknown means the durable timer is not trustworthy. */
  instagramHomeUsageState?: 'active' | 'paused' | 'unknown';
  instagramExploreBlocked: boolean;
  instagramLastDetectionAt: number;
  instagramDetectionCount: number;
  instagramSignalMask: number;
  instagramLastDetectionReason: string;
  adultSiteEnabled: boolean;
  adultSiteCustomCount: number;
  browserSignalMask: number;
  /** Optional for compatibility with an older installed native module; missing is unknown. */
  appAvailability?: SupportedAppAvailability;
  /** Optional for compatibility with an older installed native module; missing is unknown. */
  browserAvailability?: SupportedBrowserAvailability;
};

export type AdultSiteSettings = {
  available: boolean;
  enabled: boolean;
  customHosts: string[];
  browserSignalMask: number;
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
  hasCurrentConsent(): Promise<boolean>;
  acceptCurrentConsent(): Promise<void>;
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
  getAdultSiteSettings(): Promise<AdultSiteSettings>;
  setAdultSiteBlockingEnabled(enabled: boolean): Promise<void>;
  addBlockedDomain(input: string): Promise<string>;
  removeBlockedDomain(host: string): Promise<void>;
  openBrowserCheck(): Promise<void>;
}

export default requireNativeModule<ZenGuardModule>('ZenGuard');
