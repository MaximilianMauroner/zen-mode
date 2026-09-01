import { hasProtectionPassword } from '@/features/protection/credential';
import {
  getZenGuardStatus,
  openAccessibilitySettings,
  openYouTube,
  setNativeProtectionEnabled,
  type ZenGuardStatus,
} from '@/features/protection/native';
import { AlertTriangle, CheckCircle2, Eye, LockKeyhole, Play, Settings2, ShieldCheck } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

export default function HomeScreen() {
  const [status, setStatus] = useState<ZenGuardStatus | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    setError('');
    try {
      if (!(await hasProtectionPassword())) {
        router.replace('/setup');
        return;
      }
      setStatus(await getZenGuardStatus());
    } catch {
      setError('Zen Mode could not read the Android protection service.');
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  const pullToRefresh = async () => {
    setRefreshing(true);
    await refresh();
    setRefreshing(false);
  };

  const enableProtection = async () => {
    await setNativeProtectionEnabled(true);
    await refresh();
  };

  const serviceHealthy = status?.available && status.serviceEnabled;
  const isProtected = serviceHealthy && status?.protectionEnabled;

  return (
    <SafeAreaView className="flex-1 bg-cream" edges={['top']}>
      <ScrollView
        contentContainerClassName="mx-auto w-full max-w-xl px-5 pb-12 pt-5"
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={pullToRefresh} tintColor="#436753" />}>
        <View className="flex-row items-start justify-between">
          <View className="flex-1 pr-5">
            <Text className="text-sm font-medium tracking-wide text-moss">ZEN MODE</Text>
            <Text className="mt-1 text-3xl font-semibold tracking-tight text-ink">Your attention guard</Text>
          </View>
          <View className={`h-12 w-12 items-center justify-center rounded-2xl ${isProtected ? 'bg-moss' : 'bg-clay'}`}>
            {isProtected ? <ShieldCheck color="#FCFBF7" size={24} /> : <AlertTriangle color="#FCFBF7" size={23} />}
          </View>
        </View>

        <View className={`mt-7 rounded-[30px] p-6 ${isProtected ? 'bg-ink' : 'bg-paper'}`}>
          <Text className={`text-sm font-semibold ${isProtected ? 'text-sage' : 'text-clay'}`}>
            {isProtected ? (status?.observationMode ? 'OBSERVING YOUTUBE' : 'PROTECTION ACTIVE') : 'ACTION NEEDED'}
          </Text>
          <Text className={`mt-2 text-2xl font-semibold tracking-tight ${isProtected ? 'text-paper' : 'text-ink'}`}>
            {!status?.available
              ? 'Android build required'
              : !status.serviceEnabled
                ? 'Enable accessibility access'
                : !status.protectionEnabled
                  ? 'Protection is paused'
                  : status.observationMode
                    ? status.lastDetectionAt
                      ? 'Shorts signal captured'
                      : 'Calibrate Shorts detection'
                    : 'YouTube Shorts are blocked'}
          </Text>
          <Text className={`mt-2 leading-6 ${isProtected ? 'text-mist' : 'text-moss'}`}>
            {!status?.available
              ? 'Install the Android development build to use native protection.'
              : !status.serviceEnabled
                ? 'Android controls this permission. Zen Mode will recheck it when you return.'
                : !status.protectionEnabled
                  ? 'Re-enabling protection does not require your password.'
                  : status.observationMode
                    ? status.lastDetectionAt
                      ? 'The phone recognized a strong viewer signal. Activate enforcement when ready.'
                      : 'Open Shorts once. Zen Mode records only a detection counter and timestamp.'
                    : 'A confirmed Shorts viewer triggers one Back action, with a bounded Home fallback.'}
          </Text>

          {status?.available && !status.serviceEnabled ? (
            <Pressable className="mt-5 items-center rounded-2xl bg-ink py-3.5 active:opacity-80" onPress={openAccessibilitySettings}>
              <Text className="font-semibold text-paper">Open Android settings</Text>
            </Pressable>
          ) : null}
          {status?.available && status.serviceEnabled && !status.protectionEnabled ? (
            <Pressable className="mt-5 items-center rounded-2xl bg-ink py-3.5 active:opacity-80" onPress={enableProtection}>
              <Text className="font-semibold text-paper">Re-enable protection</Text>
            </Pressable>
          ) : null}
          {isProtected && status?.observationMode && status.lastDetectionAt ? (
            <Pressable className="mt-5 items-center rounded-2xl bg-paper py-3.5 active:opacity-80" onPress={() => router.push('/unlock?intent=enforce')}>
              <Text className="font-semibold text-ink">Activate with password</Text>
            </Pressable>
          ) : null}
        </View>

        {isProtected && status?.observationMode ? (
          <View className="mt-5 rounded-3xl border border-mist bg-paper p-5">
            <View className="flex-row items-center">
              <Eye color="#436753" size={20} />
              <Text className="ml-3 text-base font-semibold text-ink">Observation mode</Text>
            </View>
            <View className="mt-5 flex-row">
              <View className="flex-1">
                <Text className="text-xs font-medium text-sage">YOUTUBE EVENTS</Text>
                <Text className="mt-1 text-2xl font-semibold text-ink">{status.lastEventAt ? 'Seen' : 'None'}</Text>
              </View>
              <View className="flex-1 border-l border-mist pl-5">
                <Text className="text-xs font-medium text-sage">SHORTS SIGNALS</Text>
                <Text className="mt-1 text-2xl font-semibold text-ink">{status.detectionCount}</Text>
              </View>
            </View>
            <Text className="mt-4 text-xs leading-5 text-moss">No page text, video titles, searches, or account data are stored.</Text>
          </View>
        ) : null}

        <Text className="mt-8 text-xl font-semibold tracking-tight text-ink">Controls</Text>
        <View className="mt-4 overflow-hidden rounded-3xl border border-mist bg-paper px-5">
          <ControlRow icon={<Play color="#436753" size={20} />} label="Open YouTube" detail="Use your normal signed-in app" onPress={openYouTube} />
          <ControlRow icon={<Settings2 color="#436753" size={20} />} label="Accessibility settings" detail="Review the Android permission" onPress={openAccessibilitySettings} />
          {status?.protectionEnabled ? (
            <ControlRow icon={<LockKeyhole color="#D88568" size={20} />} label="Disable protection" detail="Requires your password" onPress={() => router.push('/unlock?intent=disable')} />
          ) : null}
        </View>

        <View className="mt-5 flex-row rounded-2xl bg-mist px-4 py-3.5">
          <CheckCircle2 color="#436753" size={18} />
          <Text className="ml-3 flex-1 text-sm leading-5 text-moss">Only the YouTube package is in scope. Other apps are ignored.</Text>
        </View>
        {error ? <Text className="mt-4 text-sm font-medium text-clay">{error}</Text> : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function ControlRow({ icon, label, detail, onPress }: { icon: React.ReactNode; label: string; detail: string; onPress: () => void }) {
  return (
    <Pressable className="flex-row items-center border-b border-mist py-4 last:border-b-0 active:opacity-70" onPress={onPress}>
      <View className="h-10 w-10 items-center justify-center rounded-2xl bg-mist">{icon}</View>
      <View className="ml-4 flex-1">
        <Text className="font-semibold text-ink">{label}</Text>
        <Text className="mt-0.5 text-sm text-moss">{detail}</Text>
      </View>
    </Pressable>
  );
}
