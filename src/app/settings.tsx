import { useRef, useState } from 'react';
import { RefreshControl, Text, View } from 'react-native';
import Constants from 'expo-constants';
import { router } from 'expo-router';

import { DangerButton, PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { Row, RowGroup } from '@/components/ui/card';
import { ErrorNote, Screen, ScreenHeader } from '@/components/ui/screen';
import { useWeakeningGate, WeakeningGateDialog } from '@/components/ui/weakening-gate';
import { openFeedbackIssue } from '@/features/feedback/github-issue';
import { getFeedPresentation } from '@/features/protection/feed-presentation';
import { authorizeWeakening } from '@/features/protection/authorize-weakening';
import { getZenGuardStatus, openAccessibilitySettings, openInstagram, openYouTube, openX, setNativeProtectionEnabled } from '@/features/protection/native';
import { useGuardStatus } from '@/features/protection/use-guard-status';
import { colors } from '@/theme/colors';

/** System controls stay separate from feed settings and whole-app budgets. */
export default function SettingsScreen() {
  const { status, loading, readError, refresh } = useGuardStatus();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const busyRef = useRef(false);
  const gate = useWeakeningGate();
  const runAction = (task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    void task().catch((cause: unknown) => {
      setError(cause instanceof Error ? cause.message : 'The action could not finish.');
    }).finally(() => { busyRef.current = false; setBusy(false); });
  };
  const statusKnown = Boolean(status?.available);
  const protectionLabel = !statusKnown ? loading ? 'Checking' : 'Unavailable' : !status?.serviceEnabled ? 'Access needed' : status.protectionEnabled ? 'Running' : 'Paused';
  const disabled = busy || loading || !status?.available;
  const changeProtection = () => runAction(async () => {
    const fresh = await getZenGuardStatus();
    if (!fresh.available) return;
    if (!await authorizeWeakening(fresh.protectionEnabled, 'pause protection', gate.request)) return;
    const current = await getZenGuardStatus();
    if (current.protectionEnabled !== fresh.protectionEnabled) throw new Error('Protection changed. Review it and try again.');
    await setNativeProtectionEnabled(!current.protectionEnabled);
    await refresh();
  });

  return (
    <Screen refreshControl={<RefreshControl refreshing={loading} onRefresh={() => { if (!busy) void refresh(); }} tintColor={colors.accent} />}>
      <ScreenHeader label="SETTINGS" onBack={() => router.back()} backDisabled={busy} />
      <RowGroup>
        <Row label="Android access" detail="Needed to apply your rules. Tap to open Android settings." statusLabel={statusKnown ? status?.serviceEnabled ? 'Granted' : 'Needed' : loading ? 'Checking' : 'Unavailable'} tone={statusKnown && status?.serviceEnabled ? 'accent' : 'neutral'} disabled={disabled} onPress={() => runAction(openAccessibilitySettings)} />
        <Row label="Protection" detail={!statusKnown ? 'The current status could not be read.' : !status?.serviceEnabled ? 'Rules cannot run without Android access.' : status.protectionEnabled ? 'Applies configured rules. Feed status is shown below.' : 'Rules are saved but are not being applied.'} statusLabel={protectionLabel} tone={protectionLabel === 'Running' ? 'accent' : 'neutral'} />
      </RowGroup>
      {statusKnown && !status?.serviceEnabled ? (
        <PrimaryButton title="Open Android settings" disabled={disabled} onPress={() => runAction(openAccessibilitySettings)} />
      ) : status?.protectionEnabled ? (
        <DangerButton title={busy ? 'Working…' : 'Pause protection'} disabled={disabled} onPress={changeProtection} />
      ) : status?.available ? (
        <PrimaryButton title={busy ? 'Working…' : 'Resume protection'} disabled={disabled} onPress={changeProtection} />
      ) : null}
      <ErrorNote message={error || readError} />
      <WeakeningGateDialog gate={gate} />
      {error ? <SecondaryButton title="Manage lock" disabled={busy} onPress={() => router.navigate('/lock')} /> : null}
      {readError ? <SecondaryButton title="Try again" disabled={busy || loading} onPress={() => void refresh()} /> : null}
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">Guard details</Text>
        <RowGroup>
          <Row label="Shorts detection" detail={status ? `${status.detectionCount} detections recorded` : 'Status unavailable'} value={status ? status.lastDetectionAt > 0 ? 'FOUND' : 'WAITING' : '—'} />
          <Row label="YouTube Shorts" {...getFeedPresentation(status, 'shorts', loading)} />
          <Row label="YouTube home feed" {...getFeedPresentation(status, 'youtubeHome', loading)} />
          <Row label="Instagram checks" detail="Messages and a Reel opened from a message" value={status ? `${Number((status.instagramSignalMask & 1) !== 0) + Number((status.instagramSignalMask & 2) !== 0)}/2` : '—'} />
          <Row label="Instagram Reels" {...getFeedPresentation(status, 'reels', loading)} />
          <Row label="Instagram home feed" {...getFeedPresentation(status, 'home', loading)} />
          <Row label="Instagram Explore" {...getFeedPresentation(status, 'explore', loading)} />
          <Row label="X home feed" {...getFeedPresentation(status, 'xHome', loading)} />
          <Row label="X videos" {...getFeedPresentation(status, 'xVideos', loading)} />
        </RowGroup>
      </View>
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-[18px] font-semibold text-copy">About</Text>
        <RowGroup>
          <Row label="Zen Mode" detail="Version installed on this device" value={Constants.expoConfig?.version ?? 'Unknown'} />
        </RowGroup>
        <SecondaryButton title="View statistics" onPress={() => router.navigate('/stats')} disabled={busy} />
        <SecondaryButton title="Privacy" onPress={() => router.navigate('/privacy')} disabled={busy} />
        <SecondaryButton title="Send feedback" disabled={busy} onPress={() => runAction(openFeedbackIssue)} />
        <SecondaryButton title="Check YouTube app opener" disabled={disabled} onPress={() => runAction(openYouTube)} />
        <SecondaryButton title="Check Instagram app opener" disabled={disabled} onPress={() => runAction(openInstagram)} />
        <SecondaryButton title="Check X app opener" disabled={disabled} onPress={() => runAction(openX)} />
      </View>
    </Screen>
  );
}
