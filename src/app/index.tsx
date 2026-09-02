import { hasCompletedSetup, isPasswordProtectionEnabled } from '@/features/protection/credential';
import {
  getZenGuardStatus,
  openAccessibilitySettings,
  openInstagram,
  openYouTube,
  setNativeProtectionEnabled,
  setObservationMode,
  type ZenGuardStatus,
} from '@/features/protection/native';
import { CheckCircle2, ShieldCheck } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { Pressable, RefreshControl, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

type ReadState = 'loading' | 'ready' | 'error';

export default function HomeScreen() {
  const [status, setStatus] = useState<ZenGuardStatus | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [readState, setReadState] = useState<ReadState>('loading');
  const [busy, setBusy] = useState(false);
  const [passwordProtectionEnabled, setPasswordProtectionEnabled] = useState<boolean | null>(null);
  const busyRef = useRef(false);
  const refreshInFlightRef = useRef(false);

  const refresh = useCallback(async () => {
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setError('');
    setReadState('loading');
    setStatus(null);
    setPasswordProtectionEnabled(null);
    try {
      if (!(await hasCompletedSetup())) {
        router.replace('/setup');
        return;
      }
      const [nextStatus, passwordEnabled] = await Promise.all([getZenGuardStatus(), isPasswordProtectionEnabled()]);
      setStatus(nextStatus);
      setPasswordProtectionEnabled(passwordEnabled);
      setReadState('ready');
    } catch {
      setStatus(null);
      setPasswordProtectionEnabled(null);
      setReadState('error');
      setError('Zen Mode could not read the Android protection service.');
    } finally {
      refreshInFlightRef.current = false;
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh]),
  );

  const pullToRefresh = async () => {
    if (refreshInFlightRef.current || busyRef.current) return;
    setRefreshing(true);
    try {
      await refresh();
    } catch {
      setReadState('error');
      setError('Zen Mode could not read the Android protection service.');
    } finally {
      setRefreshing(false);
    }
  };

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

  const enableProtection = () => {
    if (!statusLoaded) return setError('Protection status is not available yet.');
    runAction(async () => {
      await setNativeProtectionEnabled(true);
      await refresh();
    }, 'Protection could not be re-enabled.');
  };

  const activateYouTubeEnforcement = () => {
    if (!statusLoaded) return setError('Protection status is not available yet.');
    runAction(async () => {
      if (passwordProtectionEnabled === true) {
        router.push('/unlock?intent=enforce');
        return;
      }
      await setObservationMode(false);
      await refresh();
    }, 'YouTube enforcement could not be activated.');
  };

  const disableProtection = () => {
    if (!statusLoaded) return setError('Protection status is not available yet.');
    runAction(async () => {
      if (passwordProtectionEnabled === true) {
        router.push('/unlock?intent=disable');
        return;
      }
      await setNativeProtectionEnabled(false);
      await refresh();
    }, 'Protection could not be disabled.');
  };

  const openYouTubeApp = () => runAction(() => openYouTube(), 'YouTube could not be opened.');
  const openInstagramApp = () => runAction(() => openInstagram(), 'Instagram could not be opened.');
  const openSettings = () => runAction(() => openAccessibilitySettings(), 'Android settings could not be opened.');
  const openInstagramControls = () => runAction(async () => router.push('/instagram'), 'Instagram settings could not be opened.');
  const openPasswordControls = () => runAction(async () => router.push('/password-protection'), 'Password settings could not be opened.');

  const currentStatus = readState === 'ready' ? status : null;
  const statusLoaded = currentStatus !== null && passwordProtectionEnabled !== null;
  const serviceHealthy = currentStatus !== null && currentStatus.available && currentStatus.serviceEnabled;
  const isProtected = serviceHealthy && currentStatus !== null && currentStatus.protectionEnabled;
  const instagramSignalsSeen = currentStatus
    ? Number((currentStatus.instagramSignalMask & 1) !== 0) + Number((currentStatus.instagramSignalMask & 2) !== 0)
    : 0;
  const guardProgress = currentStatus === null ? 0 : isProtected && !currentStatus.observationMode ? 100 : Math.round((instagramSignalsSeen / 2) * 100);
  const protectionLabel =
    currentStatus === null
      ? readState === 'loading'
        ? 'CHECKING'
        : 'UNAVAILABLE'
      : !currentStatus.available
        ? 'BUILD NEEDED'
        : !currentStatus.serviceEnabled
          ? 'OFFLINE'
          : !currentStatus.protectionEnabled
            ? 'PAUSED'
            : currentStatus.observationMode
              ? 'OBSERVING'
              : 'ACTIVE';
  const youtubeLabel =
    currentStatus === null
      ? 'CHECKING'
      : !currentStatus.available || !currentStatus.serviceEnabled
        ? 'OFF'
        : !currentStatus.protectionEnabled
          ? 'PAUSED'
          : currentStatus.observationMode
            ? 'WATCHING'
            : 'BLOCKED';
  const instagramLabel =
    currentStatus === null
      ? 'CHECKING'
      : !currentStatus.available || !currentStatus.serviceEnabled || !currentStatus.protectionEnabled
        ? 'OFF'
        : currentStatus.instagramObservationMode
          ? 'OBSERVING'
          : 'GUARDED';
  const statusTitle =
    currentStatus === null
      ? readState === 'loading'
        ? 'Reading your guard'
        : 'Protection status unavailable'
      : !currentStatus.available
        ? 'Android build required'
        : !currentStatus.serviceEnabled
          ? 'Enable accessibility access'
          : !currentStatus.protectionEnabled
            ? 'Protection is paused'
            : currentStatus.observationMode
              ? currentStatus.lastDetectionAt
                ? 'Signals are ready'
                : 'Finish the first check'
              : 'A little more room to think';
  const statusDescription =
    currentStatus === null
      ? readState === 'loading'
        ? 'Checking the Android service and your password settings.'
        : 'Zen Mode cannot safely show or change protection until those settings can be read.'
      : !currentStatus.available
        ? 'Install the Android development build to use native protection.'
        : !currentStatus.serviceEnabled
          ? 'Android controls this permission. Zen Mode will recheck it when you return.'
          : !currentStatus.protectionEnabled
            ? 'Re-enabling protection does not require your password.'
            : currentStatus.observationMode
              ? currentStatus.lastDetectionAt
                ? 'YouTube and Instagram are ready. Start enforcement when you want.'
                : 'Open Shorts once, then visit Direct Messages and one DM Reel.'
              : 'Shorts are blocked. Instagram rules are active.';

  return (
    <SafeAreaView className="flex-1 bg-night" edges={['top']}>
      <ScrollView
        contentContainerClassName="mx-auto w-full max-w-xl px-4 pb-12 pt-4"
        refreshControl={<RefreshControl refreshing={refreshing || readState === 'loading'} onRefresh={pullToRefresh} tintColor="#A7E782" />}>
        <View className="flex-row items-center justify-between">
          <View className="flex-row items-center">
            <ShieldCheck color="#A7E782" size={18} />
            <Text className="ml-2 text-sm font-bold tracking-widest text-accent">ZEN MODE</Text>
          </View>
          <StatusPill label={currentStatus === null ? protectionLabel : protectionLabel === 'ACTIVE' ? 'ON' : protectionLabel} tone={isProtected ? 'accent' : currentStatus === null ? 'neutral' : 'danger'} />
        </View>

        <Text className="mt-9 text-xs font-bold tracking-widest text-muted">TODAY&apos;S GUARD</Text>
        <Text className="mt-3 text-4xl font-semibold leading-tight tracking-tight text-copy">
          {currentStatus !== null && isProtected ? (
            <>
              A little more{ '\n' }
              <Text className="text-accent">room to think.</Text>
            </>
          ) : (
            statusTitle
          )}
        </Text>
        <Text className="mt-3 max-w-md text-base leading-6 text-muted">{statusDescription}</Text>

        <View className="mt-6 rounded-3xl border border-line bg-panel p-4">
          <View className="flex-row items-center justify-between">
            <Text className="text-xs font-bold tracking-widest text-muted">PROTECTION</Text>
            <StatusPill label={protectionLabel} tone={isProtected ? 'accent' : 'danger'} />
          </View>
          <Text className="mt-4 text-4xl font-bold tracking-tight text-accent" style={{ fontVariant: ['tabular-nums'] }}>
            {currentStatus === null ? '...' : currentStatus.detectionCount}
          </Text>
          <Text className="mt-1 text-sm text-muted">Shorts signals recorded</Text>
          <View className="mt-4 h-1.5 overflow-hidden rounded-full bg-track">
            <View className="h-full rounded-full bg-accent" style={{ width: `${guardProgress}%` }} />
          </View>
          <View className="mt-4 flex-row">
            <QuietStat label="IG SIGNALS" value={currentStatus === null ? '...' : `${instagramSignalsSeen}/2`} />
            <QuietStat label="PASSWORD" value={passwordProtectionEnabled === null ? '...' : passwordProtectionEnabled ? 'ON' : 'OFF'} />
          </View>

          {statusLoaded && currentStatus.available && !currentStatus.serviceEnabled ? (
            <ActionButton title="Open Android settings" disabled={busy} onPress={openSettings} />
          ) : null}
          {statusLoaded && currentStatus.available && currentStatus.serviceEnabled && !currentStatus.protectionEnabled ? (
            <ActionButton title={busy ? 'Updating' : 'Re-enable protection'} disabled={busy} onPress={enableProtection} />
          ) : null}
          {isProtected && currentStatus.observationMode && currentStatus.lastDetectionAt ? (
            <ActionButton title={busy ? 'Updating' : 'Activate enforcement'} disabled={busy} onPress={activateYouTubeEnforcement} />
          ) : null}
        </View>

        <Text className="mt-8 text-xs font-bold tracking-widest text-muted">CONTROLS</Text>
        <View className="mt-3 overflow-hidden rounded-3xl border border-line bg-panel px-4">
          <ControlRow label="YouTube Shorts" detail="Shorts stay out of the loop" value={youtubeLabel} tone={youtubeLabel === 'BLOCKED' ? 'accent' : 'neutral'} disabled={!statusLoaded || busy} onPress={openYouTubeApp} />
          <ControlRow label="Instagram guard" detail={currentStatus ? `${currentStatus.instagramWaitSeconds}s pause / ${currentStatus.instagramHomeMinutes}m Home` : 'Reading rules'} value={instagramLabel} tone={instagramLabel === 'GUARDED' ? 'accent' : 'neutral'} disabled={!statusLoaded || busy} onPress={openInstagramControls} />
          <ControlRow label="Messages" detail="Always available" value="OPEN" tone="accent" />
          <ControlRow
            label="Password protection"
            detail={passwordProtectionEnabled === null ? 'Reading setting' : passwordProtectionEnabled ? 'Guards weaker changes' : 'Changes stay open'}
            value={passwordProtectionEnabled === null ? '...' : passwordProtectionEnabled ? 'ON' : 'OFF'}
            disabled={!statusLoaded || busy}
            onPress={openPasswordControls}
          />
          <ControlRow
            label="Accessibility access"
            detail="Android permission"
            value={currentStatus === null ? '...' : currentStatus.serviceEnabled ? 'ON' : 'OFF'}
            tone={currentStatus?.serviceEnabled ? 'accent' : 'danger'}
            disabled={!statusLoaded || busy}
            onPress={openSettings}
          />
          {currentStatus?.protectionEnabled ? (
            <ControlRow label="Disable protection" detail={passwordProtectionEnabled === true ? 'Requires your password' : 'Password protection is off'} value="TURN OFF" tone="danger" disabled={!statusLoaded || busy} onPress={disableProtection} />
          ) : null}
        </View>

        <Pressable className="mt-4 items-center rounded-2xl bg-accent py-3.5 active:opacity-80" disabled={!statusLoaded || busy} onPress={openInstagramApp}>
          <Text className="font-bold text-onAccent">{busy ? 'Working' : 'Open Instagram'}</Text>
        </Pressable>
        <View className="mt-4 flex-row rounded-2xl border border-line bg-panel2 px-4 py-3.5">
          <CheckCircle2 color="#A7E782" size={18} />
          <Text className="ml-3 flex-1 text-sm leading-5 text-muted">Only YouTube and Instagram are watched. Everything else stays out.</Text>
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

function QuietStat({ label, value }: { label: string; value: string }) {
  return (
    <View className="flex-1 border-t border-line pt-2.5 first:border-t-0">
      <Text className="text-[10px] font-bold tracking-widest text-muted">{label}</Text>
      <Text className="mt-1 text-base font-bold text-copy">{value}</Text>
    </View>
  );
}

function ActionButton({ title, disabled, onPress }: { title: string; disabled?: boolean; onPress: () => void }) {
  return (
    <Pressable className={`mt-4 items-center rounded-2xl bg-accent py-3.5 ${disabled ? 'opacity-50' : 'active:opacity-80'}`} disabled={disabled} onPress={onPress}>
      <Text className="font-bold text-onAccent">{title}</Text>
    </Pressable>
  );
}

function ControlRow({ label, detail, value, tone = 'neutral', disabled, onPress }: { label: string; detail: string; value?: string; tone?: 'accent' | 'danger' | 'neutral'; disabled?: boolean; onPress?: () => void }) {
  const content = (
    <>
      <View className="flex-1 pr-3">
        <Text className="font-semibold text-copy">{label}</Text>
        <Text className="mt-0.5 text-sm text-muted">{detail}</Text>
      </View>
      {value ? <StatusPill label={value} tone={tone} /> : null}
    </>
  );

  return onPress ? (
    <Pressable className={`flex-row items-center border-b border-line py-3.5 ${disabled ? 'opacity-50' : 'active:opacity-70'}`} disabled={disabled} onPress={onPress}>
      {content}
    </Pressable>
  ) : (
    <View className="flex-row items-center border-b border-line py-3.5">{content}</View>
  );
}
