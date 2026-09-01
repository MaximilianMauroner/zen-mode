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
      instagramObservationMode: true,
      instagramWaitSeconds: 30,
      instagramReelsMinutes: 5,
      instagramHomeMinutes: 5,
      instagramExploreBlocked: true,
      instagramLastDetectionAt: 0,
      instagramDetectionCount: 0,
      instagramSignalMask: 0,
      instagramLastDetectionReason: '',
    };
  }

  async openAccessibilitySettings() {}
  async openYouTube() {}
  async openInstagram() {}
  async setProtectionEnabled() {}
  async setObservationMode() {}
  async setInstagramObservationMode() {}
  async setInstagramSettings() {}
}

export default registerWebModule(ZenGuardModule, 'ZenGuard');
