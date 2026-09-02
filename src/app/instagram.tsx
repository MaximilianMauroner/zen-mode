import {
  getZenGuardStatus,
  openInstagram,
  setInstagramObservationMode,
  setInstagramSettings,
  type ZenGuardStatus,
} from '@/features/protection/native';
import { isPasswordProtectionEnabled } from '@/features/protection/credential';
import { ArrowLeft, Camera, Eye, LockKeyhole, ShieldCheck } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

const WAIT_OPTIONS = [15, 30, 60, 120, 300];
const REELS_OPTIONS = [1, 5, 10, 15];
const HOME_OPTIONS = [1, 5, 10, 15, 30];
type ReadState = 'loading' | 'ready' | 'error';

export default function InstagramSettingsScreen() {
  const [status, setStatus] = useState<ZenGuardStatus | null>(null);
  const [error, setError] = useState('');
  const [readState, setReadState] = useState<ReadState>('loading');
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const refreshInFlightRef = useRef(false);

  const refresh = useCallback(async () => {
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setReadState('loading');
    setStatus(null);
    setError('');
    try {
      setStatus(await getZenGuardStatus());
      setReadState('ready');
    } catch {
      setStatus(null);
      setReadState('error');
      setError('Instagram protection settings could not be loaded.');
    } finally {
      refreshInFlightRef.current = false;
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh]),
  );

  const runAction = useCallback((task: () => Promise<void>, fallbackMessage: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    void (async () => {
      try {
        await task();
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : fallbackMessage);
      } finally {
        busyRef.current = false;
        setBusy(false);
      }
    })();
  }, []);

  const changeSettings = (next: Partial<Pick<ZenGuardStatus, 'instagramWaitSeconds' | 'instagramReelsMinutes' | 'instagramHomeMinutes' | 'instagramExploreBlocked'>>) => {
    if (!statusLoaded || !currentStatus) {
      setError('Instagram protection settings are not available yet.');
      return;
    }

    const proposed = { ...currentStatus, ...next };
    const weakensProtection =
      proposed.instagramWaitSeconds < currentStatus.instagramWaitSeconds ||
      proposed.instagramReelsMinutes > currentStatus.instagramReelsMinutes ||
      proposed.instagramHomeMinutes > currentStatus.instagramHomeMinutes ||
      (!proposed.instagramExploreBlocked && currentStatus.instagramExploreBlocked);

    runAction(async () => {
      if (weakensProtection && (await isPasswordProtectionEnabled())) {
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
    }, 'Instagram protection settings could not be changed.');
  };

  const activateEnforcement = () => {
    if (!statusLoaded) {
      setError('Instagram protection status is not available yet.');
      return;
    }
    runAction(async () => {
      if (await isPasswordProtectionEnabled()) {
        router.push('/unlock?intent=instagram-enforce');
        return;
      }
      await setInstagramObservationMode(false);
      await refresh();
    }, 'Instagram enforcement could not be activated.');
  };

  const openInstagramApp = () => runAction(() => openInstagram(), 'Instagram could not be opened.');
  const goBack = () => runAction(async () => router.back(), 'Could not leave Instagram settings.');
  const currentStatus = readState === 'ready' ? status : null;
  const statusLoaded = currentStatus !== null;
  const requiredSignalsSeen = currentStatus ? (currentStatus.instagramSignalMask & 3) === 3 : false;
  const signalsSeen = currentStatus
    ? Number((currentStatus.instagramSignalMask & 1) !== 0) + Number((currentStatus.instagramSignalMask & 2) !== 0)
    : 0;
  const guardLabel = currentStatus === null ? (readState === 'loading' ? 'CHECKING' : 'UNAVAILABLE') : currentStatus.instagramObservationMode ? 'OBSERVING' : 'GUARDED';

  return (
    <SafeAreaView className="flex-1 bg-night">
      <ScrollView contentContainerClassName="mx-auto w-full max-w-xl px-4 pb-12 pt-4">
        <Pressable accessibilityLabel="Go back" className="h-10 w-10 items-center justify-center rounded-xl border border-line bg-panel2" disabled={busy} onPress={goBack}>
          <ArrowLeft color="#A7E782" size={19} />
        </Pressable>
        <View className="mt-7 flex-row items-center justify-between">
          <View className="flex-row items-center">
            <Camera color="#A7E782" size={18} />
            <Text className="ml-2 text-sm font-bold tracking-widest text-accent">INSTAGRAM</Text>
          </View>
          <StatusPill label={guardLabel} tone={currentStatus?.instagramObservationMode ? 'neutral' : currentStatus ? 'accent' : 'neutral'} />
        </View>
        <Text className="mt-8 text-4xl font-semibold tracking-tight text-copy">Your rules.</Text>
        <Text className="mt-2 text-base leading-6 text-muted">DM Reels stay. Infinite feeds do not.</Text>

        <View className="mt-5 rounded-3xl border border-line bg-panel p-4">
          <View className="flex-row items-center justify-between">
            <View className="flex-row items-center">
              {currentStatus?.instagramObservationMode ? <Eye color="#A7E782" size={17} /> : <ShieldCheck color="#A7E782" size={17} />}
              <Text className="ml-2 text-xs font-bold tracking-widest text-muted">SIGNAL CHECK</Text>
            </View>
            <StatusPill label={currentStatus === null ? '...' : `${signalsSeen}/2`} tone={requiredSignalsSeen ? 'accent' : 'neutral'} />
          </View>
          <Text className="mt-3 text-sm leading-5 text-muted">
            {currentStatus === null
              ? readState === 'loading'
                ? 'Reading Instagram protection settings.'
                : 'Instagram protection status is unavailable.'
              : currentStatus.instagramObservationMode
                ? requiredSignalsSeen
                  ? 'Both signals were seen. Enforcement can start.'
                  : 'Open Direct Messages and one Reel from a DM. No content is stored.'
                : 'Instagram enforcement is active. Messages stay available.'}
          </Text>
          {currentStatus?.instagramObservationMode && requiredSignalsSeen ? (
            <Pressable className="mt-4 items-center rounded-2xl bg-accent py-3.5 active:opacity-80" disabled={busy} onPress={activateEnforcement}>
              <Text className="font-bold text-onAccent">{busy ? 'Updating' : 'Activate enforcement'}</Text>
            </Pressable>
          ) : null}
        </View>

        <SettingCard title="Pause before Reels" detail="Give yourself a moment before the feed opens." value={currentStatus?.instagramWaitSeconds ? `${currentStatus.instagramWaitSeconds}s` : '...'}>
          <PresetRow values={WAIT_OPTIONS} selected={currentStatus?.instagramWaitSeconds} suffix="s" disabled={!statusLoaded || busy} onSelect={(value) => changeSettings({ instagramWaitSeconds: value })} />
        </SettingCard>
        <SettingCard title="Reels window" detail="Time granted after waiting." value={currentStatus?.instagramReelsMinutes ? `${currentStatus.instagramReelsMinutes}m` : '...'}>
          <PresetRow values={REELS_OPTIONS} selected={currentStatus?.instagramReelsMinutes} suffix="m" disabled={!statusLoaded || busy} onSelect={(value) => changeSettings({ instagramReelsMinutes: value })} />
        </SettingCard>
        <SettingCard title="Home feed allowance" detail="Only counts while Instagram is in front." value={currentStatus?.instagramHomeMinutes ? `${currentStatus.instagramHomeMinutes}m` : '...'}>
          <PresetRow values={HOME_OPTIONS} selected={currentStatus?.instagramHomeMinutes} suffix="m" disabled={!statusLoaded || busy} onSelect={(value) => changeSettings({ instagramHomeMinutes: value })} />
        </SettingCard>

        <View className="mt-2 rounded-3xl border border-line bg-panel p-4">
          <View className="flex-row items-start justify-between">
            <View className="flex-1 pr-3">
              <Text className="font-semibold text-copy">Explore</Text>
              <Text className="mt-1 text-sm leading-5 text-muted">Keep the ambient path closed.</Text>
            </View>
            <Text className="text-sm font-bold text-accent">{currentStatus === null ? '...' : currentStatus.instagramExploreBlocked ? 'OFF' : 'OPEN'}</Text>
          </View>
          <Pressable
            className={`mt-4 items-center rounded-2xl border py-3.5 ${currentStatus?.instagramExploreBlocked ? 'border-line bg-panel2' : 'border-dangerLine bg-dangerBg'} ${!statusLoaded || busy ? 'opacity-50' : 'active:opacity-80'}`}
            disabled={!statusLoaded || busy}
            onPress={() => changeSettings({ instagramExploreBlocked: !currentStatus?.instagramExploreBlocked })}>
            <Text className={currentStatus?.instagramExploreBlocked ? 'font-bold text-copy' : 'font-bold text-danger'}>
              {currentStatus === null ? 'Status unavailable' : currentStatus.instagramExploreBlocked ? 'Explore blocked' : 'Explore enabled'}
            </Text>
          </Pressable>
        </View>

        <Pressable className="mt-5 items-center rounded-2xl border border-line bg-panel2 py-3.5 active:opacity-80" disabled={!statusLoaded || busy} onPress={openInstagramApp}>
          <Text className="font-bold text-copy">{busy ? 'Working' : 'Open Instagram'}</Text>
        </Pressable>
        <View className="mt-4 flex-row items-center rounded-2xl border border-line bg-panel2 px-4 py-3">
          <LockKeyhole color="#A7E782" size={17} />
          <Text className="ml-3 flex-1 text-xs leading-5 text-muted">Weaker settings ask for your password when protection is on.</Text>
        </View>
        {error ? <Text className="mt-4 text-sm font-medium text-danger">{error}</Text> : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function StatusPill({ label, tone }: { label: string; tone: 'accent' | 'danger' | 'neutral' }) {
  const className = tone === 'accent' ? 'border-accent bg-accent text-onAccent' : tone === 'danger' ? 'border-dangerLine bg-dangerBg text-danger' : 'border-line bg-panel2 text-muted';
  return <Text className={`rounded-full border px-2.5 py-1 text-[10px] font-bold tracking-widest ${className}`}>{label}</Text>;
}

function SettingCard({ title, detail, value, children }: { title: string; detail: string; value?: string; children: React.ReactNode }) {
  return (
    <View className="mt-2 rounded-3xl border border-line bg-panel p-4">
      <View className="flex-row items-start justify-between">
        <View className="flex-1 pr-3">
          <Text className="font-semibold text-copy">{title}</Text>
          <Text className="mt-1 text-sm leading-5 text-muted">{detail}</Text>
        </View>
        {value ? <Text className="text-sm font-bold text-accent">{value}</Text> : null}
      </View>
      {children}
    </View>
  );
}

function PresetRow({ values, selected, suffix, disabled, onSelect }: { values: number[]; selected?: number; suffix: string; disabled?: boolean; onSelect: (value: number) => void }) {
  return (
    <View className="mt-4 flex-row flex-wrap gap-2">
      {values.map((value) => (
        <Pressable
          key={value}
          className={`min-w-14 items-center rounded-xl border px-3 py-2.5 ${selected === value ? 'border-accent bg-accent' : 'border-line bg-panel2'} ${disabled ? 'opacity-50' : 'active:opacity-80'}`}
          disabled={disabled}
          onPress={() => onSelect(value)}>
          <Text className={selected === value ? 'font-bold text-onAccent' : 'font-bold text-muted'}>{value}{suffix}</Text>
        </Pressable>
      ))}
    </View>
  );
}
