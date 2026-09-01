import { NativeModule, registerWebModule } from 'expo';
import type { ZenGuardStatus } from './ZenGuardModule';

// ZenGuardModule is not available on the web platform.
class ZenGuardModule extends NativeModule<{}> {
  async getStatus(): Promise<ZenGuardStatus> {
    return {
      available: false,
      serviceEnabled: false,
      protectionEnabled: false,
      observationMode: true,
      lastEventAt: 0,
      lastDetectionAt: 0,
      detectionCount: 0,
      lastDetectionReason: '',
    };
  }

  async openAccessibilitySettings() {}
  async openYouTube() {}
  async setProtectionEnabled() {}
  async setObservationMode() {}
}

export default registerWebModule(ZenGuardModule, 'ZenGuard');
