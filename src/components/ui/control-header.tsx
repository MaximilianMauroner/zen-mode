import { useState } from 'react';
import { Animated, Image, Pressable, Text, View } from 'react-native';
import { Link, router, usePathname } from 'expo-router';
import { Settings } from 'lucide-react-native';
import { type MaterialTopTabNavigationOptions } from 'expo-router/js-top-tabs';
import { type ParamListBase, type TabNavigationState } from 'expo-router/react-navigation';
import { useSharedGuardStatus } from '@/features/protection/guard-status-context';
import { getFeedStatus } from '@/features/protection/feed-status';

import { colors } from '@/theme/colors';

const tabs = [
  { href: '/', label: 'Feeds' },
  { href: '/limits', label: 'App limits' },
  { href: '/lock', label: 'Lock' },
] as const;

const SUMMARY = {
  unknown: 'Checking protection',
  unavailable: 'Android app required',
  permission: 'Android access needed',
  paused: 'Protection off',
  empty: 'No feed/site rules enabled',
  setup: 'Feed/site setup needed',
  partial: 'Feed/site partly ready',
  'targets-unavailable': 'No available feed/site targets',
  'targets-unknown': 'Feed/site availability unknown',
  active: 'Feed/site protection active for ready rules',
} as const;

type ControlHeaderProps = {
  position: Animated.AnimatedInterpolation<number>;
  state: TabNavigationState<ParamListBase>;
  descriptors: Record<string, { options: MaterialTopTabNavigationOptions }>;
};

/** Navigator-owned chrome stays outside the swipeable and scrollable panels. */
export function ControlHeader({ position, state: navigationState, descriptors }: ControlHeaderProps) {
  const pathname = usePathname();
  const [width, setWidth] = useState(0);
  const disabled = descriptors[navigationState.routes[navigationState.index].key].options.swipeEnabled === false;
  const { status, readError } = useSharedGuardStatus();
  const state = getFeedStatus(status);
  const summary = readError
    ? 'Status unavailable'
    : SUMMARY[state];
  const summaryTone = state === 'active' ? 'text-accent' : readError || state === 'paused' || state === 'permission' ? 'text-danger' : 'text-copy';
  return (
    <View className="mx-auto w-full max-w-xl gap-3 px-5 pt-3">
      <View className="flex-row items-center justify-between">
        <View className="flex-row items-center gap-2">
          <Image source={require('@/assets/images/refuge/mark-header.png')} style={{ width: 32, height: 32 }} accessibilityIgnoresInvertColors />
          <Text className="text-caption font-bold tracking-[0.14em] text-copy">ZEN MODE</Text>
        </View>
        <Pressable accessibilityRole="button" accessibilityState={{ disabled }} disabled={disabled} onPress={() => router.push('/settings')} className={`-mr-2 min-h-12 flex-row items-center gap-1.5 px-2 ${disabled ? 'opacity-40' : 'active:opacity-60'}`}>
          <Settings color={colors.muted} size={17} />
          <Text className="text-support font-medium text-muted">Settings</Text>
        </Pressable>
      </View>
      <View className="flex-row items-center gap-4 pb-1">
        <Image source={require('@/assets/images/refuge/artwork.jpg')} style={{ width: 64, height: 64, borderRadius: 16, borderWidth: 1, borderColor: colors.line }} resizeMode="cover" accessibilityIgnoresInvertColors />
        <Text accessibilityLiveRegion="polite" className={`flex-1 text-title font-semibold ${summaryTone}`}>{summary}</Text>
      </View>
      <View className="flex-row border-b border-line" accessibilityRole="tablist" onLayout={(event) => setWidth(event.nativeEvent.layout.width)}>
        {tabs.map(({ href, label }) => (
          <Link key={href} href={href} asChild>
            <Pressable disabled={disabled} role="tab" aria-selected={pathname === href} aria-disabled={disabled} className={`min-h-12 flex-1 items-center justify-center px-1 py-3 ${disabled ? 'opacity-40' : 'active:opacity-60'}`}>
              <Text className={`text-center text-body ${pathname === href ? 'font-semibold text-accent' : 'font-medium text-muted'}`}>{label}</Text>
            </Pressable>
          </Link>
        ))}
        <Animated.View
          pointerEvents="none"
          style={{
            position: 'absolute', bottom: 0, left: 0, height: 2,
            width: width / tabs.length, backgroundColor: colors.accent,
            transform: [{ translateX: Animated.multiply(position, width / tabs.length) }],
          }}
        />
      </View>
    </View>
  );
}
