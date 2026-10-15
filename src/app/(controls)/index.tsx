import { useCallback, useRef, useState } from 'react';
import { RefreshControl, Text, View } from 'react-native';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { Clapperboard, Globe2, House, LockKeyhole, Play } from 'lucide-react-native';

import { AdultSiteControlsDrawer } from '@/components/ui/adult-site-controls-drawer';
import { FeedControlsDrawer, type FeedDrawer } from '@/components/ui/feed-controls-drawer';
import { PrimaryButton } from '@/components/ui/button';
import { GuardNotes } from '@/components/ui/guard-notes';
import { TopTabs } from 'expo-router/js-top-tabs';
import { Card, Row, RowGroup } from '@/components/ui/card';
import { Screen } from '@/components/ui/screen';
import { formatRemaining, readLockState, type LockState } from '@/features/protection/lock';
import { getFeedPresentation } from '@/features/protection/feed-presentation';
import { getFeedStatus, getFeedStatusDetail } from '@/features/protection/feed-status';
import { getHomeFeedTimeLabel } from '@/features/protection/home-feed-time';
import { getOverviewAction } from '@/features/protection/overview-actions';
import { getAdultSitePresentation } from '@/features/protection/adult-site-presentation';
import { actionFailure, IDLE_ACTION, isReading, readValue, RUNNING_ACTION, type ActionState } from '@/features/protection/control-state';
import { useSharedGuardStatus } from '@/features/protection/guard-status-context';
import { hasCompletedSetup } from '@/features/protection/setup';
import { shouldShowCustomizePrompt } from '@/features/protection/setup-policy';
import { openAccessibilitySettings, openBrowserCheck, openInstagram, openYouTube, setInstagramObservationMode, setNativeProtectionEnabled, setObservationMode } from '@/features/protection/native';
import { colors } from '@/theme/colors';

export default function FeedsScreen() {
  const { setup } = useLocalSearchParams<{ setup?: string }>();
  const { read: statusRead, refresh } = useSharedGuardStatus();
  const status = readValue(statusRead);
  const loading = isReading(statusRead);
  const readError = statusRead.kind === 'failed' ? statusRead.message : '';
  const [lock, setLock] = useState<LockState | null>(null);
  const [action, setAction] = useState<ActionState>(IDLE_ACTION);
  const [drawer, setDrawer] = useState<FeedDrawer | null>(null);
  const [siteDrawerOpen, setSiteDrawerOpen] = useState(false);
  const busy = action.kind === 'running';
  const busyRef = useRef(false);

  useFocusEffect(useCallback(() => {
    let active = true;
    const read = async () => {
      try {
        const nextLock = await readLockState();
        if (active) setLock(nextLock);
      } catch {
        if (active) setLock(null);
      }
    };
    void hasCompletedSetup().then((complete) => {
      if (!active) return;
      if (!complete) router.replace('/setup');
      else void read();
    }).catch(() => {
      if (active) setAction({ kind: 'failed', message: 'Could not read setup. Open Settings or try again.' });
    });
    // Only the visible overview needs a current lock countdown.
    const timer = setInterval(() => void read(), 60_000);
    return () => { active = false; clearInterval(timer); };
  }, []));

  const runAction = (task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setAction(RUNNING_ACTION);
    void task().then(
      () => setAction(IDLE_ACTION),
      (cause: unknown) => setAction(actionFailure(cause, 'The action could not finish. Try again.')),
    ).finally(() => { busyRef.current = false; });
  };

  const state = getFeedStatus(status);
  const editable = Boolean(status?.available) && !loading && !busy;
  const adultSites = getAdultSitePresentation(status, loading);
  const instagramHomeTime = getHomeFeedTimeLabel(status, 'instagram');
  const xHomeTime = getHomeFeedTimeLabel(status, 'x');

  const nextAction = (() => {
    switch (getOverviewAction(status, Boolean(readError))) {
      case 'retry': return { title: 'Try again', run: refresh };
      case 'open-accessibility': return { title: 'Open Android settings', run: openAccessibilitySettings };
      case 'resume-protection': return { title: 'Resume protection', run: async () => { await setNativeProtectionEnabled(true); await refresh(); } };
      case 'check-youtube': return { title: 'Check YouTube feeds', run: openYouTube };
      case 'limit-shorts': return { title: 'Limit Shorts to one', run: async () => { await setObservationMode(false); await refresh(); } };
      case 'check-instagram': return { title: 'Check Instagram feeds', run: openInstagram };
      case 'start-instagram': return { title: 'Start Instagram protection', run: async () => { await setInstagramObservationMode(false); await refresh(); } };
      case 'set-up-x': return { title: 'Set up X', run: async () => { setDrawer('x'); } };
      case 'check-sites': return { title: 'Open default browser to check', run: openBrowserCheck };
      default: return null;
    }
  })();

  return (
    <Screen edges={[]} refreshControl={<RefreshControl refreshing={loading} onRefresh={() => { if (!busy) void refresh(); }} tintColor={colors.accent} />}>
      <TopTabs.Screen options={{ swipeEnabled: !busy && drawer === null && !siteDrawerOpen }} />

      {shouldShowCustomizePrompt(setup, status) ? <Card emphasis>
        <Text accessibilityRole="header" className="text-item font-semibold text-copy">Choose your rules</Text>
        <Text className="mt-1 text-support text-muted">Open YouTube, Instagram, X, and Sites below to review their starting values. Protection is paused until you decide to resume it.</Text>
      </Card> : null}
      {status ? <Text accessibilityLiveRegion="polite" className="text-body text-muted">{getFeedStatusDetail(status)}</Text> : null}
      {nextAction ? (
        <View className="gap-2">
          {state === 'setup' && (status?.shortsEnabled && status.observationMode || status?.instagramObservationMode) ? <Text className="text-support text-muted">{status?.observationMode ? status.lastDetectionAt ? 'Shorts detected. Blocking is ready.' : 'Open one Short, then return here. Zen Mode checks the current YouTube screen before limiting scrolling; unknown layouts stay unblocked.' : ((status?.instagramSignalMask ?? 0) & 3) === 3 ? 'Instagram checks passed. Protection is ready.' : 'Open Direct Messages, then one Reel from a message. Return here when done.'}</Text> : null}
          <PrimaryButton title={busy ? 'Working…' : nextAction.title} disabled={busy || loading} onPress={() => runAction(nextAction.run)} />
        </View>
      ) : null}
      <GuardNotes read={statusRead} action={action} />

      <RowGroup>
        <Row icon={Play} label="YouTube" detail={`Home: ${getFeedPresentation(status, 'youtubeHome', loading).statusLabel} · Shorts: ${getFeedPresentation(status, 'shorts', loading).statusLabel}`} onPress={() => setDrawer('youtube')} disabled={!editable} />
        <Row icon={Clapperboard} label="Instagram" detail={`Reels: ${getFeedPresentation(status, 'reels', loading).statusLabel} · Home: ${instagramHomeTime ?? getFeedPresentation(status, 'home', loading).statusLabel} · Explore: ${getFeedPresentation(status, 'explore', loading).statusLabel}`} onPress={() => setDrawer('instagram')} disabled={!editable} />
        <Row icon={House} label="X" detail={`Home: ${xHomeTime ?? getFeedPresentation(status, 'xHome', loading).statusLabel} · Videos: ${getFeedPresentation(status, 'xVideos', loading).statusLabel}`} onPress={() => setDrawer('x')} disabled={!editable} />
        <Row
          icon={Globe2}
          label="Sites"
          {...adultSites}
          onPress={() => setSiteDrawerOpen(true)}
          disabled={!editable}
        />
      </RowGroup>

      <RowGroup>
        <Row
          icon={LockKeyhole}
          label={lock === null ? 'Check settings lock' : lock.kind === 'open' ? 'Lock your settings' : `${lock.kind === 'pending' ? 'Unlocks in' : 'Settings locked ·'} ${formatRemaining(lock.endsAt)}`}
          accessibilityLabel="Manage settings lock"
          disabled={busy}
          onPress={() => router.navigate('/lock')}
        />
      </RowGroup>

      <FeedControlsDrawer feed={drawer} onClose={() => setDrawer(null)} />
      <AdultSiteControlsDrawer visible={siteDrawerOpen} onClose={() => setSiteDrawerOpen(false)} />
    </Screen>
  );
}
