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
};

declare class ZenGuardModule extends NativeModule<{}> {
  getStatus(): Promise<ZenGuardStatus>;
  openAccessibilitySettings(): Promise<void>;
  openYouTube(): Promise<void>;
  setProtectionEnabled(enabled: boolean): Promise<void>;
  setObservationMode(enabled: boolean): Promise<void>;
}

export default requireNativeModule<ZenGuardModule>('ZenGuard');
