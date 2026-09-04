import { Card, CardHeader, Row, RowGroup, SectionLabel } from '@/components/ui/card';
import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { ErrorNote, Screen, ScreenHeader, ScreenTitle } from '@/components/ui/screen';
import { formatRemaining, isChangeBlocked, readLockState, type LockState } from '@/features/protection/lock';
import { hasCompletedSetup } from '@/features/protection/setup';
import {
  getAppLimits,
  getIntentApps,
  getRollingLimits,
  getZenGuardStatus,
  openAccessibilitySettings,
  openInstagram,
  openYouTube,
  setNativeProtectionEnabled,
  setObservationMode,
  type ZenGuardStatus,
} from '@/features/protection/native';
import { colors } from '@/theme/colors';
import { ShieldCheck } from 'lucide-react-native';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import { RefreshControl, Text, View } from 'react-native';

type ReadState = 'loading' | 'ready' | 'error';

/** The one setup action the guard still needs, if any. */
type NextStep = 'accessibility' | 'reenable' | 'enforce' | null;

const NEXT_STEP_TITLES = {
  accessibility: 'Open Android settings',
  reenable: 'Turn the guard back on',
  enforce: 'Start blocking',
} as const satisfies Record<NonNullable<NextStep>, string>;

export default function HomeScreen() {
  const [status, setStatus] = useState<ZenGuardStatus | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [readState, setReadState] = useState<ReadState>('loading');
  const [busy, setBusy] = useState(false);
  const [lock, setLock] = useState<LockState | null>(null);
  const [ruleCount, setRuleCount] = useState<number | null>(null);
  const busyRef = useRef(false);
  const refreshInFlightRef = useRef(false);

  const refresh = useCallback(async () => {
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    setError('');
    setReadState('loading');
    setStatus(null);
    setLock(null);
    setRuleCount(null);
    try {
      if (!(await hasCompletedSetup())) {
        router.replace('/setup');
        return;
      }
      const [nextStatus, nextLock, nextLimits, nextIntents, nextRolling] = await Promise.all([
        getZenGuardStatus(),
        readLockState(),
        getAppLimits(),
        getIntentApps(),
        getRollingLimits(),
      ]);
      setStatus(nextStatus);
      setLock(nextLock);
      setRuleCount(new Set([...nextLimits, ...nextIntents, ...nextRolling].map((rule) => rule.packageName)).size);
      setReadState('ready');
    } catch {
      setStatus(null);
      setLock(null);
      setRuleCount(null);
      setReadState('error');
      setError('Zen Mode could not read the Android service.');
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
      setError('Zen Mode could not read the Android service.');
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
    if (!statusLoaded) return setError('The guard status is not ready yet.');
    runAction(async () => {
      await setNativeProtectionEnabled(true);
      await refresh();
    }, 'The guard could not be turned back on.');
  };

  // Turning enforcement on only tightens the guard, so the lock never blocks it.
  const activateYouTubeEnforcement = () => {
    if (!statusLoaded) return setError('The guard status is not ready yet.');
    runAction(async () => {
      await setObservationMode(false);
      await refresh();
    }, 'Blocking could not be turned on.');
  };

  const disableProtection = () => {
    if (!statusLoaded) return setError('The guard status is not ready yet.');
    runAction(async () => {
      if (await isChangeBlocked()) {
        setError('The lock is on. Ask to unlock, then wait a day.');
        router.push('/lock');
        return;
      }
      await setNativeProtectionEnabled(false);
      await refresh();
    }, 'The guard could not be turned off.');
  };

  const openYouTubeApp = () => runAction(() => openYouTube(), 'YouTube could not be opened.');
  const openInstagramApp = () => runAction(() => openInstagram(), 'Instagram could not be opened.');
  const openSettings = () => runAction(() => openAccessibilitySettings(), 'Android settings could not be opened.');
  const openInstagramControls = () => runAction(async () => router.push('/instagram'), 'The Instagram limits could not be opened.');
  const openLockControls = () => runAction(async () => router.push('/lock'), 'The lock screen could not be opened.');
  const openAppLimits = () => runAction(async () => router.push('/limits'), 'The app rules could not be opened.');

  const currentStatus = readState === 'ready' ? status : null;
  const statusLoaded = currentStatus !== null && lock !== null;
  const serviceHealthy = currentStatus !== null && currentStatus.available && currentStatus.serviceEnabled;
  const isProtected = serviceHealthy && currentStatus !== null && currentStatus.protectionEnabled;
  const enforcing = isProtected && currentStatus !== null && !currentStatus.observationMode;
  const instagramSignalsSeen = currentStatus
    ? Number((currentStatus.instagramSignalMask & 1) !== 0) + Number((currentStatus.instagramSignalMask & 2) !== 0)
    : 0;
  const youtubeSignalSeen = currentStatus !== null && currentStatus.lastDetectionAt > 0;
  // Setup milestones, in the order they are completed. The bar only claims 100%
  // once enforcement is actually running.
  const setupSteps = [
    currentStatus?.serviceEnabled === true,
    currentStatus?.protectionEnabled === true,
    instagramSignalsSeen === 2,
    youtubeSignalSeen,
    enforcing,
  ];
  const setupProgress = currentStatus === null ? 0 : Math.round((setupSteps.filter(Boolean).length / setupSteps.length) * 100);
  const protectionLabel =
    currentStatus === null
      ? readState === 'loading'
        ? 'CHECKING'
        : 'UNAVAILABLE'
      : !currentStatus.available
        ? 'NO BUILD'
        : !currentStatus.serviceEnabled
          ? 'OFF'
          : !currentStatus.protectionEnabled
            ? 'PAUSED'
            : currentStatus.observationMode
              ? 'WATCHING'
              : 'BLOCKING';
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
          ? 'WATCHING'
          : 'LIMITED';
  const appLimitLabel = ruleCount === null ? '—' : ruleCount === 0 ? 'NONE' : `${ruleCount}`;
  const lockLabel = lock === null ? '—' : lock.kind === 'open' ? 'OPEN' : formatRemaining(lock.endsAt);
  const lockDetail =
    lock === null
      ? 'Reading the lock'
      : lock.kind === 'open'
        ? 'You can loosen limits any time'
        : lock.kind === 'pending'
          ? 'Unlock asked for. Opens in ' + formatRemaining(lock.endsAt)
          : 'Your limits are held';

  const statusTitle =
    currentStatus === null
      ? readState === 'loading'
        ? 'Checking your setup'
        : 'Cannot read the guard'
      : !currentStatus.available
        ? 'Needs the Android build'
        : !currentStatus.serviceEnabled
          ? 'Turn on accessibility access'
          : !currentStatus.protectionEnabled
            ? 'The guard is off'
            : currentStatus.observationMode
              ? currentStatus.lastDetectionAt
                ? 'Ready when you are'
                : 'One check to go'
              : 'A little more';
  const observationStep = !youtubeSignalSeen && instagramSignalsSeen < 2
    ? 'Open Shorts once, then Direct Messages and one Reel from a DM.'
    : !youtubeSignalSeen
      ? 'Instagram is done. Open YouTube Shorts once to finish.'
      : 'YouTube is done. Open Direct Messages and one Reel from a DM.';
  const statusDescription =
    currentStatus === null
      ? readState === 'loading'
        ? 'Reading the Android service and your lock.'
        : 'Nothing can change until Zen Mode can read these settings.'
      : !currentStatus.available
        ? 'Blocking needs the Android build installed.'
        : !currentStatus.serviceEnabled
          ? 'Android owns this switch. Zen Mode checks again when you come back.'
          : !currentStatus.protectionEnabled
            ? 'You can turn it back on whenever. The lock never stands in the way.'
            : currentStatus.observationMode
              ? currentStatus.lastDetectionAt
                ? 'Both apps are ready. Start blocking when you want.'
                : observationStep
              : 'Shorts are blocked and your Instagram limits are running.';

  // At most one accent button per screen. Setup steps outrank the Instagram shortcut.
  const nextStep: NextStep =
    statusLoaded && currentStatus.available && !currentStatus.serviceEnabled
      ? 'accessibility'
      : statusLoaded && currentStatus.available && currentStatus.serviceEnabled && !currentStatus.protectionEnabled
        ? 'reenable'
        : isProtected && currentStatus.observationMode && currentStatus.lastDetectionAt
          ? 'enforce'
          : null;
  const runNextStep = () => {
    if (nextStep === 'accessibility') return openSettings();
    if (nextStep === 'reenable') return enableProtection();
    if (nextStep === 'enforce') return activateYouTubeEnforcement();
  };

  return (
    <Screen refreshControl={<RefreshControl refreshing={refreshing || readState === 'loading'} onRefresh={pullToRefresh} tintColor={colors.accent} />}>
      <ScreenHeader
        icon={ShieldCheck}
        label="ZEN MODE"
        pill={{
          label: protectionLabel,
          tone: isProtected ? 'accent' : currentStatus === null ? 'neutral' : 'danger',
          solid: enforcing,
        }}
      />

      <View className="mt-3">
        <SectionLabel>TODAY</SectionLabel>
        <View className="mt-2.5">
          <ScreenTitle title={statusTitle} highlight={enforcing ? 'room to think.' : undefined} description={statusDescription} />
        </View>
      </View>

      <Card emphasis={enforcing}>
        <CardHeader label="THE GUARD" pill={{ label: protectionLabel, tone: isProtected ? 'accent' : 'danger' }} />
        <View className="-mx-3 mt-4 flex-row">
          <Metric label={enforcing ? 'SHORTS BLOCKED' : 'SHORTS SEEN'} value={currentStatus === null ? '—' : String(currentStatus.detectionCount)} accent />
          <View className="w-px bg-line" />
          <Metric label="IG CHECKS" value={currentStatus === null ? '—' : `${instagramSignalsSeen}/2`} />
          <View className="w-px bg-line" />
          <Metric label="LOCK" value={lockLabel} />
        </View>
        <View className="mt-4 h-1 overflow-hidden rounded-full bg-track">
          <View className="h-full rounded-full bg-accent" style={{ width: `${setupProgress}%` }} />
        </View>
        <Text className="mt-2 text-[12px] text-faint">{enforcing ? 'Blocking is on' : `Setup ${setupProgress}% done`}</Text>
        {nextStep ? <PrimaryButton className="mt-4" title={busy ? 'Working…' : NEXT_STEP_TITLES[nextStep]} disabled={busy} onPress={runNextStep} /> : null}
      </Card>

      <View>
        <SectionLabel className="mb-2.5">WATCHED APPS</SectionLabel>
        <RowGroup>
          <Row
            label="YouTube Shorts"
            detail="Shorts stay out of the loop"
            value={youtubeLabel}
            tone={youtubeLabel === 'BLOCKED' ? 'accent' : 'neutral'}
            disabled={!statusLoaded || busy}
            onPress={openYouTubeApp}
          />
          <Row
            label="Instagram"
            detail={currentStatus ? `${currentStatus.instagramWaitSeconds}s pause · ${currentStatus.instagramHomeMinutes}m home feed` : 'Reading limits'}
            value={instagramLabel}
            tone={instagramLabel === 'LIMITED' ? 'accent' : 'neutral'}
            disabled={!statusLoaded || busy}
            onPress={openInstagramControls}
          />
          <Row label="Messages" detail="Always available" value="OPEN" tone="accent" />
        </RowGroup>
      </View>

      <View>
        <SectionLabel className="mb-2.5">DAILY BUDGETS</SectionLabel>
        <RowGroup>
          <Row
            label="App rules"
            detail="Budgets, visits and refills per app"
            value={appLimitLabel}
            tone={ruleCount !== null && ruleCount > 0 ? 'accent' : 'neutral'}
            disabled={!statusLoaded || busy}
            onPress={openAppLimits}
          />
          <Row
            label="Lock"
            detail={lockDetail}
            value={lockLabel}
            tone={lock === null || lock.kind === 'open' ? 'neutral' : lock.kind === 'pending' ? 'danger' : 'accent'}
            disabled={!statusLoaded || busy}
            onPress={openLockControls}
          />
        </RowGroup>
      </View>

      <View>
        <SectionLabel className="mb-2.5">SYSTEM</SectionLabel>
        <RowGroup>
          <Row
            label="Accessibility access"
            detail="Android permission"
            value={currentStatus === null ? '—' : currentStatus.serviceEnabled ? 'ON' : 'OFF'}
            tone={currentStatus?.serviceEnabled ? 'accent' : 'danger'}
            disabled={!statusLoaded || busy}
            onPress={openSettings}
          />
          {currentStatus?.protectionEnabled ? (
            <Row
              label="Turn off the guard"
              detail={lock !== null && lock.kind !== 'open' ? 'The lock is holding this' : 'Stops watching and blocking'}
              destructive
              disabled={!statusLoaded || busy}
              onPress={disableProtection}
            />
          ) : null}
        </RowGroup>
      </View>

      {nextStep ? (
        <SecondaryButton title={busy ? 'Working…' : 'Open Instagram'} disabled={!statusLoaded || busy} onPress={openInstagramApp} />
      ) : (
        <PrimaryButton title={busy ? 'Working…' : 'Open Instagram'} disabled={!statusLoaded || busy} onPress={openInstagramApp} />
      )}

      <Text className="text-center text-[12px] leading-[18px] text-faint">Zen Mode only ever looks at YouTube and Instagram.</Text>
      <ErrorNote message={error} />
    </Screen>
  );
}

/** One column of the protection card's metric strip. */
function Metric({ label, value, accent = false }: { label: string; value: string; accent?: boolean }) {
  return (
    <View className="flex-1 px-3">
      <Text className="text-[10px] font-bold tracking-[0.14em] text-faint">{label}</Text>
      <Text className={`mt-1.5 text-[22px] font-bold tracking-tight ${accent ? 'text-accent' : 'text-copy'}`} style={{ fontVariant: ['tabular-nums'] }}>
        {value}
      </Text>
    </View>
  );
}
