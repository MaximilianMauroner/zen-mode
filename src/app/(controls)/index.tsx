import { useCallback, useRef, useState } from 'react';
import { Pressable, RefreshControl, Text, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { Clapperboard, Globe2, House, LockKeyhole, Play } from 'lucide-react-native';

import { AdultSiteControlsDrawer } from '@/components/ui/adult-site-controls-drawer';
import { FeedControlsDrawer, type FeedDrawer } from '@/components/ui/feed-controls-drawer';
import { PrimaryButton } from '@/components/ui/button';
import { TopTabs } from 'expo-router/js-top-tabs';
import { Row, RowGroup } from '@/components/ui/card';
import { ErrorNote, Screen } from '@/components/ui/screen';
import { formatRemaining, readLockState, type LockState } from '@/features/protection/lock';
import { getFeedPresentation } from '@/features/protection/feed-presentation';
import { getFeedStatus } from '@/features/protection/feed-status';
import { getHomeFeedTimeLabel } from '@/features/protection/home-feed-time';
import { getOverviewAction } from '@/features/protection/overview-actions';
import { getAdultSitePresentation } from '@/features/protection/adult-site-presentation';
import { useSharedGuardStatus } from '@/features/protection/guard-status-context';
import { hasCompletedSetup } from '@/features/protection/setup';
import { openAccessibilitySettings, openInstagram, openYouTube, setInstagramObservationMode, setNativeProtectionEnabled, setObservationMode } from '@/features/protection/native';
import { colors } from '@/theme/colors';

export default function FeedsScreen() {
  const { status, loading, readError, refresh } = useSharedGuardStatus();
  const [lock, setLock] = useState<LockState | null>(null);
  const [error, setError] = useState('');
  const [drawer, setDrawer] = useState<FeedDrawer | null>(null);
  const [siteDrawerOpen, setSiteDrawerOpen] = useState(false);
  const [busy, setBusy] = useState(false);
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
      if (active) setError('Could not read setup. Open Settings or try again.');
    });
    // Only the visible overview needs a current lock countdown.
    const timer = setInterval(() => void read(), 60_000);
    return () => { active = false; clearInterval(timer); };
  }, []));

  const runAction = (task: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    void task().catch((cause: unknown) => {
      setError(cause instanceof Error ? cause.message : 'The action could not finish. Try again.');
    }).finally(() => { busyRef.current = false; setBusy(false); });
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
      case 'check-youtube': return { title: 'Check YouTube Shorts', run: openYouTube };
      case 'limit-shorts': return { title: 'Limit Shorts to one', run: async () => { await setObservationMode(false); await refresh(); } };
      case 'check-instagram': return { title: 'Check Instagram feeds', run: openInstagram };
      case 'start-instagram': return { title: 'Start Instagram protection', run: async () => { await setInstagramObservationMode(false); await refresh(); } };
      case 'set-up-x': return { title: 'Set up X', run: async () => { setDrawer('x'); } };
      default: return null;
    }
  })();

  return (
    <Screen edges={[]} refreshControl={<RefreshControl refreshing={loading} onRefresh={() => { if (!busy) void refresh(); }} tintColor={colors.accent} />}>
      <TopTabs.Screen options={{ swipeEnabled: !busy && drawer === null && !siteDrawerOpen }} />

      {nextAction ? (
        <View className="gap-2">
          {state === 'setup' && (status?.shortsEnabled && status.observationMode || status?.instagramObservationMode) ? <Text className="text-[13px] leading-[19px] text-muted">{status?.observationMode ? status.lastDetectionAt ? 'Shorts detected. Blocking is ready.' : 'Open Shorts once, then return here.' : ((status?.instagramSignalMask ?? 0) & 3) === 3 ? 'Instagram checks passed. Protection is ready.' : 'Open Direct Messages, then one Reel from a message. Return here when done.'}</Text> : null}
          <PrimaryButton title={busy ? 'Working…' : nextAction.title} disabled={busy || loading} onPress={() => runAction(nextAction.run)} />
        </View>
      ) : null}
      <ErrorNote message={error || readError} />

      <View className="gap-3">
        <RowGroup>
          <Row icon={Play} label="YouTube" {...getFeedPresentation(status, 'shorts', loading)} onPress={() => setDrawer('youtube')} disabled={!editable} />
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
      </View>

      <Pressable accessibilityRole="button" accessibilityLabel="Manage settings lock" disabled={busy} onPress={() => router.navigate('/lock')} className="flex-row items-center gap-3 rounded-2xl border border-line2 bg-panel2 p-4 active:opacity-60">
        <LockKeyhole color={colors.accent} size={18} />
        <View className="flex-1">
          <Text className="text-[14px] font-semibold text-copy">{lock === null ? 'Check settings lock' : lock.kind === 'open' ? 'Lock your settings' : `${lock.kind === 'pending' ? 'Unlocks in' : 'Settings locked ·'} ${formatRemaining(lock.endsAt)}`}</Text>
        </View>
      </Pressable>

      <FeedControlsDrawer feed={drawer} onClose={() => setDrawer(null)} />
      <AdultSiteControlsDrawer visible={siteDrawerOpen} onClose={() => setSiteDrawerOpen(false)} />
    </Screen>
  );
}
