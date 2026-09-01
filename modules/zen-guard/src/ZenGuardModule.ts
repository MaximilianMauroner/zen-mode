import { NativeModule, requireNativeModule } from 'expo';

export type ZenGuardStatus = {
  available: boolean;
  serviceEnabled: boolean;
  protectionEnabled: boolean;
  observationMode: boolean;
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

declare class ZenGuardModule extends NativeModule<{}> {
  getStatus(): Promise<ZenGuardStatus>;
  openAccessibilitySettings(): Promise<void>;
  openYouTube(): Promise<void>;
  openInstagram(): Promise<void>;
  setProtectionEnabled(enabled: boolean): Promise<void>;
  setObservationMode(enabled: boolean): Promise<void>;
  setInstagramObservationMode(enabled: boolean): Promise<void>;
  setInstagramSettings(waitSeconds: number, reelsMinutes: number, homeMinutes: number, exploreBlocked: boolean): Promise<void>;
}

export default requireNativeModule<ZenGuardModule>('ZenGuard');
