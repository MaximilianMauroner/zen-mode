import { useRef, useState, type ReactNode } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useReducedMotion } from 'react-native-reanimated';
import { PrimaryButton, SecondaryButton } from './button';
import { ChipGroup } from './choice';
import { GuardNotes } from './guard-notes';
import { SheetHeader } from './screen';
import { actionFailure, IDLE_ACTION, isReading, readValue, RUNNING_ACTION, type ActionState } from '@/features/protection/control-state';
import { useSharedGuardStatus } from '@/features/protection/guard-status-context';
import { getFeedPresentation } from '@/features/protection/feed-presentation';
import { getHomeFeedTimeLabel } from '@/features/protection/home-feed-time';
import { authorizeWeakening } from '@/features/protection/authorize-weakening';
import { feedWeakeningAction } from '@/features/protection/feed-weakening';
import { useWeakeningGate, WeakeningGateDialog } from './weakening-gate';
import { getXDrawerGuidance } from '@/features/protection/x-drawer-guidance';
import { hasAllRequiredXSignals } from '@/features/protection/x-readiness';
import { hasAllRequiredYouTubeSignals } from '@/features/protection/youtube-readiness';
import { getSupportedAppAvailability, type SupportedAppKey } from '@/features/protection/target-availability';
import { getFeedDrawerTargetPresentation } from '@/features/protection/feed-drawer-presentation';
import { getZenGuardStatus, openInstagram, setInstagramObservationMode, setInstagramSettings, openX, openYouTube, setObservationMode, setYouTubeSettings, setXObservationMode, setXSettings, type ZenGuardStatus } from '@/features/protection/native';

export type FeedDrawer = 'youtube' | 'instagram' | 'x';
type Props = { feed: FeedDrawer | null; onClose: () => void };
const TITLES = { youtube: 'YouTube', instagram: 'Instagram', x: 'X' };
const HOME_MINUTES = [1, 5, 10, 15, 30];

/** Keep the feed list in place while editing cached rules in a native drawer. */
export function FeedControlsDrawer({ feed, onClose }: Props) {
  const { read, refresh } = useSharedGuardStatus();
  const status = readValue(read);
  const [action, setAction] = useState<ActionState>(IDLE_ACTION);
  const busy = action.kind === 'running';
  const inFlight = useRef(false);
  const reduceMotion = useReducedMotion();
  const gate = useWeakeningGate();
  const disabled = busy || isReading(read) || !status?.available;
  const isX = feed === 'x';
  const isInstagram = feed === 'instagram';
  const targetApp: SupportedAppKey = isInstagram ? 'instagram' : isX ? 'x' : 'youtube';
  const targetAvailability = getSupportedAppAvailability(status, targetApp);
  const observing = isInstagram ? status?.instagramObservationMode : isX ? status?.xObservationMode : status?.observationMode;
  const enabled = isInstagram ? true : isX ? status?.xHomeEnabled || status?.xVideosEnabled : status?.shortsEnabled || status?.youtubeHomeEnabled;
  const detected = status && (isInstagram ? (status.instagramSignalMask & 3) === 3 : isX ? hasAllRequiredXSignals(status) : hasAllRequiredYouTubeSignals(status));
  const canStart = detected && status?.serviceEnabled && status.protectionEnabled;
  // After setup, a feed switched on later still needs its own signal. Say so,
  // rather than leaving the user with a rule that quietly enforces nothing.
  const xGuidance = isX ? getXDrawerGuidance(status, Boolean(observing)) : { initialAwaiting: [], postSetupAwaiting: [] };
  const targetPresentation = getFeedDrawerTargetPresentation(
    targetAvailability,
    TITLES[targetApp],
    Boolean(observing),
    Boolean(enabled),
    xGuidance.postSetupAwaiting.length > 0,
  );

  const run = (task: (fresh: ZenGuardStatus) => Promise<void>) => {
    if (inFlight.current || disabled) return;
    inFlight.current = true;
    setAction(RUNNING_ACTION);
    void (async () => {
      try {
        const fresh = await getZenGuardStatus();
        if (!fresh.available) throw new Error('Feed controls need the Android app.');
        await task(fresh);
        await refresh();
        setAction(IDLE_ACTION);
      } catch (cause: unknown) {
        setAction(actionFailure(cause, 'Could not change the rule. Try again.'));
      } finally {
        inFlight.current = false;
      }
    })();
  };
  const change = (next: Partial<Pick<ZenGuardStatus, 'xHomeEnabled' | 'xVideosEnabled' | 'xHomeMinutes' | 'shortsEnabled' | 'youtubeHomeEnabled' | 'instagramWaitSeconds' | 'instagramReelsMinutes' | 'instagramHomeMinutes' | 'instagramExploreBlocked'>>) => run(async (fresh) => {
    const proposed = { ...fresh, ...next };
    const weakening = feedWeakeningAction(fresh, proposed);
    if (!await authorizeWeakening(weakening !== null, weakening ?? '', gate.request)) return;
    const current = weakening ? await getZenGuardStatus() : fresh;
    if (weakening && Object.keys(next).some((key) =>
      current[key as keyof ZenGuardStatus] !== fresh[key as keyof ZenGuardStatus])) {
      throw new Error('The rule changed while you were answering. Review it and try again.');
    }
    const approved = { ...current, ...next };
    if (isInstagram) await setInstagramSettings(approved.instagramWaitSeconds, approved.instagramReelsMinutes, approved.instagramHomeMinutes, approved.instagramExploreBlocked);
    else if (isX) await setXSettings(approved.xHomeEnabled, approved.xVideosEnabled, approved.xHomeMinutes);
    else await setYouTubeSettings(approved.shortsEnabled, approved.youtubeHomeEnabled);
  });
  const close = () => { if (!inFlight.current) onClose(); };
  const result = getFeedPresentation(status, isX ? 'xVideos' : 'shorts');
  const homeTime = isInstagram ? getHomeFeedTimeLabel(status, 'instagram') : isX ? getHomeFeedTimeLabel(status, 'x') : null;

  return (
    <Modal visible={feed !== null} transparent animationType={reduceMotion ? 'none' : 'slide'} onRequestClose={() => { if (gate.session) gate.finish(false); else close(); }} onShow={() => { setAction(IDLE_ACTION); void refresh(); }} statusBarTranslucent>
      <View className="flex-1 justify-end bg-black/50">
        <Pressable accessibilityRole="button" accessibilityLabel="Close feed controls" onPress={close} disabled={busy} className="absolute inset-0" />
        <SafeAreaView edges={['bottom']} style={{ maxHeight: '88%' }} className="rounded-t-3xl border-t border-line2 bg-panel pt-3">
          <SheetHeader title={TITLES[feed ?? 'youtube']} closeLabel="Close feed controls" disabled={busy} onClose={close} />
          <ScrollView contentContainerStyle={{ paddingHorizontal: 20, paddingBottom: 20 }}>
            {isX ? <>
              <Section title="Home feed" status={homeTime ?? getFeedPresentation(status, 'xHome').statusLabel} first>
                <RuleChoice label="X Home rule" limitedLabel="Take breaks" allowedLabel="No breaks" enabled={status?.xHomeEnabled === true} disabled={disabled} onChange={(value) => change({ xHomeEnabled: value })} />
                <Text className="text-support text-muted">Break after</Text>
                <PresetRow label="X Home break interval" values={HOME_MINUTES} selected={status?.xHomeMinutes} suffix="m" disabled={disabled || !status?.xHomeEnabled} onSelect={(value) => change({ xHomeMinutes: value })} />
              </Section>
              <ScrollingSection isX status={status} statusLabel={result.statusLabel} disabled={disabled} change={change} />
            </> : isInstagram ? <>
              <Section title="Reels" status={getFeedPresentation(status, 'reels').statusLabel} first>
                <Text className="text-support text-muted">Pause before Reels</Text>
                <PresetRow label="Pause before Reels" values={[15, 30, 60, 120, 300]} selected={status?.instagramWaitSeconds} suffix="s" disabled={disabled} onSelect={(value) => change({ instagramWaitSeconds: value })} />
                <Text className="text-support text-muted">Viewing window</Text>
                <PresetRow label="Reels viewing window" values={[1, 5, 10, 15]} selected={status?.instagramReelsMinutes} suffix="m" disabled={disabled} onSelect={(value) => change({ instagramReelsMinutes: value })} />
              </Section>
              <Section title="Home feed" status={homeTime ?? getFeedPresentation(status, 'home').statusLabel}>
                <Text className="text-support text-muted">Break after</Text>
                <PresetRow label="Instagram Home break interval" values={HOME_MINUTES} selected={status?.instagramHomeMinutes} suffix="m" disabled={disabled} onSelect={(value) => change({ instagramHomeMinutes: value })} />
              </Section>
              <Section title="Explore" status={getFeedPresentation(status, 'explore').statusLabel}>
                <RuleChoice label="Explore rule" limitedLabel="Block Explore" allowedLabel="Allow Explore" enabled={status?.instagramExploreBlocked === true} disabled={disabled} onChange={(value) => change({ instagramExploreBlocked: value })} />
              </Section>
            </> : <>
              <Section title="Home feed" status={getFeedPresentation(status, 'youtubeHome').statusLabel} first>
                <RuleChoice label="YouTube Home rule" limitedLabel="Block Home" allowedLabel="Allow Home" enabled={status?.youtubeHomeEnabled === true} disabled={disabled} onChange={(value) => change({ youtubeHomeEnabled: value })} />
                <Text className="text-support text-muted">Search, Subscriptions, library/history, channels, notifications, and opened videos stay available. Home blocking waits for a device-verified signal.</Text>
              </Section>
              <ScrollingSection isX={false} status={status} statusLabel={result.statusLabel} disabled={disabled} change={change} />
            </>}
            {targetPresentation.showObservationGuidance ? <View className="mt-5 gap-2 border-t border-line pt-5">
              {isInstagram ? <Text className="text-support text-muted">{detected ? '✓ Messages and Reels detected' : 'Open Direct Messages, then one Reel from a message. Return here when done.'}</Text> : isX ? <>
                {status?.xHomeEnabled ? <Text className="text-support text-muted">{xGuidance.initialAwaiting.includes('home') ? '○ Open the Home feed' : '✓ Home feed detected'}</Text> : null}
                {status?.xVideosEnabled ? <Text className="text-support text-muted">{xGuidance.initialAwaiting.includes('videos') ? '○ Open one video' : '✓ Video viewer detected'}</Text> : null}
              </> : <>
                {status?.youtubeHomeEnabled ? <Text className="text-support text-muted">{status.youtubeHomeObserved ? '✓ Home feed detected' : status.youtubeHomeDetectionSupported ? '○ Open the Home feed once' : '○ Home detection awaits physical-device validation'}</Text> : null}
                {status?.shortsEnabled ? <Text className="text-support text-muted">{status.lastDetectionAt > 0 ? '✓ Shorts detected' : '○ Open one Short, then return here. Zen Mode checks this YouTube screen before limiting scrolling; unknown layouts stay unblocked.'}</Text> : null}
              </>}
              {canStart ? <PrimaryButton title="Start protection" disabled={disabled} onPress={() => run(async () => { if (isInstagram) await setInstagramObservationMode(false); else if (isX) await setXObservationMode(false); else await setObservationMode(false); })} /> : detected ? <Text className="text-support text-muted">Turn on protection and Android access in Settings.</Text> : null}
            </View> : null}
            {targetPresentation.showPostSetupGuidance ? <View className="mt-5 gap-2 border-t border-line pt-5">
              {xGuidance.postSetupAwaiting.map((feed) => (
                <Text key={feed} className="text-support text-muted">
                  {feed === 'home' ? '○ Open the Home feed once to start breaks' : '○ Open one video once to start the video limit'}
                </Text>
              ))}
              <Text className="text-caption text-faint">Other X rules keep running in the meantime.</Text>
            </View> : null}
            {targetPresentation.recovery ? <Text className="mt-5 text-support text-muted">{targetPresentation.recovery}</Text> : null}
            {targetPresentation.showOpener ? (
              <SecondaryButton className="mt-5" title={isInstagram ? 'Open Instagram to check' : isX ? 'Open X to check' : 'Open YouTube to check'} disabled={disabled} onPress={() => run(async () => { if (isInstagram) await openInstagram(); else if (isX) await openX(); else await openYouTube(); })} />
            ) : null}
            <GuardNotes reads={[read]} action={action} className="mt-5" />
          </ScrollView>
        </SafeAreaView>
        <WeakeningGateDialog gate={gate} embedded />
      </View>
    </Modal>
  );
}

type ChangeRule = (next: Partial<Pick<ZenGuardStatus, 'xVideosEnabled' | 'shortsEnabled'>>) => void;

/** One rule area inside the drawer: a heading with its live state, then its controls. */
function Section({ title, status, first = false, children }: { title: string; status: string; first?: boolean; children: ReactNode }) {
  return (
    <View className={`gap-3 ${first ? '' : 'mt-5 border-t border-line pt-5'}`}>
      <View className="flex-row items-baseline justify-between gap-3">
        <Text accessibilityRole="header" className="text-item font-semibold text-copy">{title}</Text>
        <Text className="flex-shrink text-right text-caption font-semibold text-muted">{status}</Text>
      </View>
      {children}
    </View>
  );
}

function ScrollingSection({ isX, status, statusLabel, disabled, change }: { isX: boolean; status: ZenGuardStatus | null; statusLabel: string; disabled: boolean; change: ChangeRule }) {
  const limited = (isX ? status?.xVideosEnabled : status?.shortsEnabled) === true;
  return (
    <Section title={isX ? 'Video scrolling' : 'Shorts scrolling'} status={statusLabel}>
      <RuleChoice label={isX ? 'X video rule' : 'YouTube Shorts rule'} limitedLabel="Limit to one" allowedLabel="Allow scrolling" enabled={limited} disabled={disabled} onChange={(value) => change(isX ? { xVideosEnabled: value } : { shortsEnabled: value })} />
      <Text className="text-support text-muted">{limited ? `Watch one ${isX ? 'video' : 'Short'} per visit. Scrolling to another is blocked.` : 'Scrolling has no feed limit.'}</Text>
    </Section>
  );
}

function RuleChoice({ label, limitedLabel, allowedLabel, enabled, disabled, onChange }: { label: string; limitedLabel: string; allowedLabel: string; enabled: boolean; disabled: boolean; onChange: (enabled: boolean) => void }) {
  return <ChipGroup label={label} options={[{ value: true, label: limitedLabel }, { value: false, label: allowedLabel }]} selected={enabled} disabled={disabled} fill onSelect={onChange} />;
}

function PresetRow({ label, values, selected, suffix, disabled, onSelect }: { label: string; values: readonly number[]; selected?: number; suffix: string; disabled: boolean; onSelect: (value: number) => void }) {
  return <ChipGroup label={label} options={values.map((value) => ({ value, label: `${value}${suffix}` }))} selected={selected} disabled={disabled} fill onSelect={onSelect} />;
}
