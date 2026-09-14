import { Text, View } from 'react-native';
import { router } from 'expo-router';

import { Screen, ScreenHeader } from '@/components/ui/screen';

export default function PrivacyScreen() {
  return (
    <Screen>
      <ScreenHeader label="PRIVACY" onBack={() => router.back()} />
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">What the guard uses</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Zen Mode uses Android accessibility to recognize screens in YouTube, Instagram, and X. It checks the foreground app to apply whole-app limits. The app picker lists launchable apps installed on this device.</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Screen text and the full installed-app list are not saved by Zen Mode or sent to a server. The guard does not record typing or take screenshots.</Text>
      </View>
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">Data on this device</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Zen Mode saves your rules, selected app identifiers, settings lock, consent record, usage totals, session times, and detection summaries. Removing a rule does not always remove its past usage records.</Text>
      </View>
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">Your controls</Text>
        <Text className="text-[14px] leading-[21px] text-muted">You can turn off Zen Mode in Android Accessibility settings at any time. Clear storage for Zen Mode in Android settings or uninstall it to remove its local data. Zen Mode opts out of Android cloud backup. Device-to-device transfer behavior can still depend on the device manufacturer.</Text>
        <Text className="text-[14px] leading-[21px] text-muted">Privacy and support: lab4code.dev@gmail.com</Text>
      </View>
    </Screen>
  );
}
