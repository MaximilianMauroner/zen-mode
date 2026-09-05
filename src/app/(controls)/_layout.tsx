import { useEffect, useState } from 'react';
import { AccessibilityInfo } from 'react-native';
import { TopTabs } from 'expo-router/js-top-tabs';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ControlHeader } from '@/components/ui/control-header';
import { GuardStatusProvider } from '@/features/protection/guard-status-context';

import { colors } from '@/theme/colors';

/** One native pager keeps route changes, gestures, and the tab indicator in sync. */
export default function ControlsLayout() {
  const [reduceMotion, setReduceMotion] = useState(true);

  useEffect(() => {
    let mounted = true;
    void AccessibilityInfo.isReduceMotionEnabled().then((enabled) => {
      if (mounted) setReduceMotion(enabled);
    });
    const subscription = AccessibilityInfo.addEventListener('reduceMotionChanged', setReduceMotion);
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);

  return (
    <GuardStatusProvider>
      <SafeAreaView className="flex-1 bg-night" edges={['top']}>
        <TopTabs
          initialRouteName="index"
          backBehavior="initialRoute"
          tabBar={ControlHeader}
          screenOptions={{ lazy: false, animationEnabled: !reduceMotion, sceneStyle: { backgroundColor: colors.night } }}
        >
          <TopTabs.Screen name="index" options={{ title: 'Feeds' }} />
          <TopTabs.Screen name="limits" options={{ title: 'App limits' }} />
          <TopTabs.Screen name="lock" options={{ title: 'Lock' }} />
        </TopTabs>
      </SafeAreaView>
    </GuardStatusProvider>
  );
}
