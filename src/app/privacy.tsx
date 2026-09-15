import { Text, View } from 'react-native';
import { router } from 'expo-router';

import { Screen, ScreenHeader } from '@/components/ui/screen';

export default function PrivacyScreen() {
  return (
    <Screen>
      <ScreenHeader label="PRIVACY" onBack={() => router.back()} />
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">What the guard uses</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Zen Mode uses Android accessibility to recognize screens in YouTube, Instagram, and X. When website blocking is on, it reads current addresses from known browser address bars, not page content. It checks the foreground app to apply whole-app limits. The app picker lists launchable apps installed on this device.</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Browser addresses, screen text, and the full installed-app list are not saved by Zen Mode or sent to a server. The guard does not record typing or take screenshots.</Text>
      </View>
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">Data on this device</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Zen Mode saves your rules, the extra domains you add, browser readiness checks, selected app identifiers, settings lock, consent record, usage totals, session times, and detection summaries. It does not save the sites you visit. Removing a rule does not always remove its past usage records.</Text>
      </View>
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">Your controls</Text>
        <Text className="text-[14px] leading-[21px] text-muted">You can turn off Zen Mode in Android Accessibility settings at any time. Clear storage for Zen Mode in Android settings or uninstall it to remove its local data. Zen Mode opts out of Android cloud backup. Device-to-device transfer behavior can still depend on the device manufacturer.</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Opening Send feedback creates an editable GitHub issue draft and sends the app version and platform to GitHub. Nothing is submitted until you choose to submit it. Submitted issues and their contents are public.</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Privacy and support: support@lab4code.com</Text>
      </View>
    </Screen>
  );
}
