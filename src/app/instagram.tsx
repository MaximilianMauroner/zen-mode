import { Card, CardHeader, SectionLabel } from '@/components/ui/card';
import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { ErrorNote, Screen, ScreenHeader, ScreenTitle } from '@/components/ui/screen';
import { StatusPill } from '@/components/ui/pill';
import {
  getZenGuardStatus,
  openInstagram,
  setInstagramObservationMode,
  setInstagramSettings,
  type ZenGuardStatus,
} from '@/features/protection/native';
import { isChangeBlocked } from '@/features/protection/lock';
import { colors } from '@/theme/colors';
import { Eye, LockKeyhole, ShieldCheck } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { Pressable, Text, View } from 'react-native';

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
      setError('Your Instagram limits could not be loaded.');
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
      setError('Your Instagram limits are not ready yet.');
      return;
    }

    const proposed = { ...currentStatus, ...next };
    const weakensProtection =
      proposed.instagramWaitSeconds < currentStatus.instagramWaitSeconds ||
      proposed.instagramReelsMinutes > currentStatus.instagramReelsMinutes ||
      proposed.instagramHomeMinutes > currentStatus.instagramHomeMinutes ||
      (!proposed.instagramExploreBlocked && currentStatus.instagramExploreBlocked);

    runAction(async () => {
      if (weakensProtection && (await isChangeBlocked())) {
        setError('That loosens a limit while the lock is on. Ask to unlock, then wait a day.');
        router.push('/lock');
        return;
      }

      await setInstagramSettings(
        proposed.instagramWaitSeconds,
        proposed.instagramReelsMinutes,
        proposed.instagramHomeMinutes,
        proposed.instagramExploreBlocked,
      );
      await refresh();
    }, 'That limit could not be changed.');
  };

  // Turning enforcement on only tightens the guard, so the lock never blocks it.
  const activateEnforcement = () => {
    if (!statusLoaded) {
      setError('Your Instagram limits are not ready yet.');
      return;
    }
    runAction(async () => {
      await setInstagramObservationMode(false);
      await refresh();
    }, 'Blocking could not be turned on.');
  };

  const openInstagramApp = () => runAction(() => openInstagram(), 'Instagram could not be opened.');
  const goBack = () => runAction(async () => router.back(), 'Could not go back.');
  const currentStatus = readState === 'ready' ? status : null;
  const statusLoaded = currentStatus !== null;
  const requiredSignalsSeen = currentStatus ? (currentStatus.instagramSignalMask & 3) === 3 : false;
  const signalsSeen = currentStatus
    ? Number((currentStatus.instagramSignalMask & 1) !== 0) + Number((currentStatus.instagramSignalMask & 2) !== 0)
    : 0;
  const observing = currentStatus?.instagramObservationMode === true;
  const guarded = statusLoaded && !observing;
  const guardLabel = currentStatus === null ? (readState === 'loading' ? 'CHECKING' : 'UNAVAILABLE') : observing ? 'WATCHING' : 'LIMITED';
  // Enforcement is the one accent action here; without it the shortcut takes the slot.
  const canActivate = observing && requiredSignalsSeen;

  return (
    <Screen>
      <ScreenHeader
        label="INSTAGRAM"
        onBack={goBack}
        backDisabled={busy}
        pill={{ label: guardLabel, tone: guarded ? 'accent' : 'neutral', solid: guarded }}
      />

      <ScreenTitle title="Your rules." description="DM Reels stay. Infinite feeds do not." />

      <Card emphasis={guarded}>
        <CardHeader
          label="SETUP CHECK"
          icon={observing ? <Eye color={colors.accent} size={15} /> : <ShieldCheck color={colors.accent} size={15} />}
          pill={{ label: currentStatus === null ? '—' : `${signalsSeen}/2`, tone: requiredSignalsSeen ? 'accent' : 'neutral' }}
        />
        <Text className="mt-3 text-[13px] leading-[19px] text-muted">
          {currentStatus === null
            ? readState === 'loading'
              ? 'Reading your Instagram limits.'
              : 'Cannot read the Instagram limits.'
            : observing
              ? requiredSignalsSeen
                ? 'Both screens found. Blocking can start.'
                : 'Open Direct Messages, then one Reel from a DM. Nothing is stored.'
              : 'Blocking is on. Messages still work.'}
        </Text>
        {canActivate ? <PrimaryButton className="mt-4" title={busy ? 'Working…' : 'Start blocking'} disabled={busy} onPress={activateEnforcement} /> : null}
      </Card>

      <View>
        <SectionLabel className="mb-2.5">LIMITS</SectionLabel>
        <View className="gap-2.5">
          <SettingCard title="Pause before Reels" detail="Give yourself a moment before the feed opens." value={currentStatus ? `${currentStatus.instagramWaitSeconds}s` : '—'}>
            <PresetRow values={WAIT_OPTIONS} selected={currentStatus?.instagramWaitSeconds} suffix="s" disabled={!statusLoaded || busy} onSelect={(value) => changeSettings({ instagramWaitSeconds: value })} />
          </SettingCard>
          <SettingCard title="Reels window" detail="How long Reels stay open after the pause." value={currentStatus ? `${currentStatus.instagramReelsMinutes}m` : '—'}>
            <PresetRow values={REELS_OPTIONS} selected={currentStatus?.instagramReelsMinutes} suffix="m" disabled={!statusLoaded || busy} onSelect={(value) => changeSettings({ instagramReelsMinutes: value })} />
          </SettingCard>
          <SettingCard title="Home feed" detail="Counts only while Instagram is on screen." value={currentStatus ? `${currentStatus.instagramHomeMinutes}m` : '—'}>
            <PresetRow values={HOME_OPTIONS} selected={currentStatus?.instagramHomeMinutes} suffix="m" disabled={!statusLoaded || busy} onSelect={(value) => changeSettings({ instagramHomeMinutes: value })} />
          </SettingCard>
          <SettingCard title="Explore" detail="The endless grid of suggestions." value={currentStatus === null ? '—' : currentStatus.instagramExploreBlocked ? 'CLOSED' : 'OPEN'}>
            <Pressable
              accessibilityRole="switch"
              accessibilityState={{ checked: currentStatus?.instagramExploreBlocked === true, disabled: !statusLoaded || busy }}
              className={`mt-3.5 items-center rounded-2xl border py-3 ${currentStatus?.instagramExploreBlocked ? 'border-accentLine bg-accentBg' : 'border-dangerLine bg-dangerBg'} ${!statusLoaded || busy ? 'opacity-40' : 'active:opacity-70'}`}
              disabled={!statusLoaded || busy}
              onPress={() => changeSettings({ instagramExploreBlocked: !currentStatus?.instagramExploreBlocked })}>
              <Text className={`text-[15px] font-semibold ${currentStatus?.instagramExploreBlocked ? 'text-accent' : 'text-danger'}`}>
                {currentStatus === null ? 'Status unavailable' : currentStatus.instagramExploreBlocked ? 'Explore blocked' : 'Explore enabled'}
              </Text>
            </Pressable>
          </SettingCard>
        </View>
      </View>

      {canActivate ? (
        <SecondaryButton title={busy ? 'Working…' : 'Open Instagram'} disabled={!statusLoaded || busy} onPress={openInstagramApp} />
      ) : (
        <PrimaryButton title={busy ? 'Working…' : 'Open Instagram'} disabled={!statusLoaded || busy} onPress={openInstagramApp} />
      )}

      <View className="flex-row items-center rounded-2xl border border-line bg-panel2 px-4 py-3">
        <LockKeyhole color={colors.faint} size={16} />
        <Text className="ml-3 flex-1 text-[12px] leading-[18px] text-faint">While the lock is on you can tighten a limit, but not loosen it.</Text>
      </View>
      <ErrorNote message={error} />
    </Screen>
  );
}

/** A named limit with its current value and a row of preset choices. */
function SettingCard({ title, detail, value, children }: { title: string; detail: string; value: string; children: React.ReactNode }) {
  return (
    <Card>
      <View className="flex-row items-start justify-between">
        <View className="flex-1 pr-3">
          <Text className="text-[15px] font-semibold text-copy">{title}</Text>
          <Text className="mt-0.5 text-[13px] leading-[18px] text-muted">{detail}</Text>
        </View>
        <StatusPill label={value} tone="accent" />
      </View>
      {children}
    </Card>
  );
}

function PresetRow({ values, selected, suffix, disabled, onSelect }: { values: number[]; selected?: number; suffix: string; disabled?: boolean; onSelect: (value: number) => void }) {
  return (
    <View className="mt-3.5 flex-row flex-wrap gap-2">
      {values.map((value) => (
        <Pressable
          key={value}
          accessibilityRole="radio"
          accessibilityState={{ selected: selected === value, disabled }}
          className={`min-w-[52px] items-center rounded-xl border px-3 py-2 ${selected === value ? 'border-accent bg-accent' : 'border-line bg-panel2'} ${disabled ? 'opacity-40' : 'active:opacity-70'}`}
          disabled={disabled}
          onPress={() => onSelect(value)}>
          <Text className={`text-[14px] font-bold ${selected === value ? 'text-onAccent' : 'text-muted'}`}>
            {value}
            {suffix}
          </Text>
        </Pressable>
      ))}
    </View>
  );
}
