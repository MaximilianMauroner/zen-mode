import {
  getZenGuardStatus,
  openInstagram,
  setInstagramSettings,
  type ZenGuardStatus,
} from '@/features/protection/native';
import { ArrowLeft, Camera, Eye, LockKeyhole, ShieldCheck } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

const WAIT_OPTIONS = [15, 30, 60, 120, 300];
const REELS_OPTIONS = [1, 5, 10, 15];
const HOME_OPTIONS = [1, 5, 10, 15, 30];

export default function InstagramSettingsScreen() {
  const [status, setStatus] = useState<ZenGuardStatus | null>(null);
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    try {
      setStatus(await getZenGuardStatus());
      setError('');
    } catch {
      setError('Instagram protection settings could not be loaded.');
    }
  }, []);

  useFocusEffect(useCallback(() => void refresh(), [refresh]));

  const changeSettings = async (next: Partial<Pick<ZenGuardStatus, 'instagramWaitSeconds' | 'instagramReelsMinutes' | 'instagramHomeMinutes' | 'instagramExploreBlocked'>>) => {
    if (!status) return;
    const proposed = { ...status, ...next };
    const weakensProtection =
      proposed.instagramWaitSeconds < status.instagramWaitSeconds ||
      proposed.instagramReelsMinutes > status.instagramReelsMinutes ||
      proposed.instagramHomeMinutes > status.instagramHomeMinutes ||
      (!proposed.instagramExploreBlocked && status.instagramExploreBlocked);

    if (weakensProtection) {
      router.push({
        pathname: '/unlock',
        params: {
          intent: 'instagram-settings',
          waitSeconds: proposed.instagramWaitSeconds,
          reelsMinutes: proposed.instagramReelsMinutes,
          homeMinutes: proposed.instagramHomeMinutes,
          exploreBlocked: proposed.instagramExploreBlocked ? 'true' : 'false',
        },
      });
      return;
    }

    await setInstagramSettings(
      proposed.instagramWaitSeconds,
      proposed.instagramReelsMinutes,
      proposed.instagramHomeMinutes,
      proposed.instagramExploreBlocked,
    );
    await refresh();
  };

  const requiredSignalsSeen = status ? (status.instagramSignalMask & 3) === 3 : false;

  return (
    <SafeAreaView className="flex-1 bg-cream">
      <ScrollView contentContainerClassName="mx-auto w-full max-w-xl px-5 pb-12 pt-5">
        <Pressable accessibilityLabel="Go back" className="h-11 w-11 items-center justify-center rounded-full bg-paper" onPress={() => router.back()}>
          <ArrowLeft color="#436753" size={20} />
        </Pressable>
        <View className="mt-6 flex-row items-center">
          <View className="h-12 w-12 items-center justify-center rounded-2xl bg-ink">
            <Camera color="#FCFBF7" size={23} />
          </View>
          <View className="ml-4 flex-1">
            <Text className="text-3xl font-semibold tracking-tight text-ink">Instagram guard</Text>
            <Text className="mt-1 text-sm text-moss">DM Reels stay intentional. Infinite feeds do not.</Text>
          </View>
        </View>

        <View className="mt-7 rounded-3xl bg-ink p-5">
          <View className="flex-row items-center">
            {status?.instagramObservationMode ? <Eye color="#8FA997" size={20} /> : <ShieldCheck color="#8FA997" size={20} />}
            <Text className="ml-3 font-semibold text-paper">
              {status?.instagramObservationMode ? 'Observation mode' : 'Instagram enforcement active'}
            </Text>
          </View>
          <Text className="mt-3 leading-6 text-mist">
            {requiredSignalsSeen
              ? 'Direct Messages and Reels signals have been observed on this Instagram build.'
              : 'Open Direct Messages and a Reel from a DM once. No message or Reel content is stored.'}
          </Text>
          {status?.instagramObservationMode && requiredSignalsSeen ? (
            <Pressable className="mt-5 items-center rounded-2xl bg-paper py-3.5" onPress={() => router.push('/unlock?intent=instagram-enforce')}>
              <Text className="font-semibold text-ink">Activate with password</Text>
            </Pressable>
          ) : null}
        </View>

        <SettingCard title="Blocking wait" detail="Wait before Continue doomscrolling becomes available.">
          <PresetRow values={WAIT_OPTIONS} selected={status?.instagramWaitSeconds} suffix="s" onSelect={(value) => changeSettings({ instagramWaitSeconds: value })} />
        </SettingCard>
        <SettingCard title="Reels window" detail="Time granted after waiting.">
          <PresetRow values={REELS_OPTIONS} selected={status?.instagramReelsMinutes} suffix="m" onSelect={(value) => changeSettings({ instagramReelsMinutes: value })} />
        </SettingCard>
        <SettingCard title="Home feed allowance" detail="Scrolling time before Instagram is closed.">
          <PresetRow values={HOME_OPTIONS} selected={status?.instagramHomeMinutes} suffix="m" onSelect={(value) => changeSettings({ instagramHomeMinutes: value })} />
        </SettingCard>

        <View className="mt-5 rounded-3xl border border-mist bg-paper p-5">
          <Text className="text-base font-semibold text-ink">Explore page</Text>
          <Text className="mt-1 text-sm leading-5 text-moss">Explore starts blocked. Enabling it requires your password.</Text>
          <Pressable
            className={`mt-4 items-center rounded-2xl py-3.5 ${status?.instagramExploreBlocked ? 'bg-moss' : 'border border-clay bg-paper'}`}
            onPress={() => changeSettings({ instagramExploreBlocked: !status?.instagramExploreBlocked })}>
            <Text className={status?.instagramExploreBlocked ? 'font-semibold text-paper' : 'font-semibold text-clay'}>
              {status?.instagramExploreBlocked ? 'Explore blocked' : 'Explore enabled'}
            </Text>
          </Pressable>
        </View>

        <Pressable className="mt-5 items-center rounded-2xl border border-mist bg-paper py-4" onPress={openInstagram}>
          <Text className="font-semibold text-ink">Open Instagram</Text>
        </Pressable>
        <View className="mt-4 flex-row items-center rounded-2xl bg-mist px-4 py-3">
          <LockKeyhole color="#436753" size={17} />
          <Text className="ml-3 flex-1 text-xs leading-5 text-moss">Shorter waits, longer allowances, and enabling Explore require the password.</Text>
        </View>
        {error ? <Text className="mt-4 text-sm font-medium text-clay">{error}</Text> : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function SettingCard({ title, detail, children }: { title: string; detail: string; children: React.ReactNode }) {
  return (
    <View className="mt-5 rounded-3xl border border-mist bg-paper p-5">
      <Text className="text-base font-semibold text-ink">{title}</Text>
      <Text className="mt-1 text-sm leading-5 text-moss">{detail}</Text>
      {children}
    </View>
  );
}

function PresetRow({ values, selected, suffix, onSelect }: { values: number[]; selected?: number; suffix: string; onSelect: (value: number) => void }) {
  return (
    <View className="mt-4 flex-row flex-wrap gap-2">
      {values.map((value) => (
        <Pressable
          key={value}
          className={`min-w-14 items-center rounded-xl px-3 py-2.5 ${selected === value ? 'bg-ink' : 'bg-mist'}`}
          onPress={() => onSelect(value)}>
          <Text className={selected === value ? 'font-semibold text-paper' : 'font-semibold text-moss'}>{value}{suffix}</Text>
        </Pressable>
      ))}
    </View>
  );
}
