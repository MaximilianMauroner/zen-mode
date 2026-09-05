import { useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useReducedMotion } from 'react-native-reanimated';
import { PrimaryButton, SecondaryButton } from './button';
import { ErrorNote } from './screen';
import { useSharedGuardStatus } from '@/features/protection/guard-status-context';
import { getFeedPresentation } from '@/features/protection/feed-presentation';
import { isChangeBlocked } from '@/features/protection/lock';
import { getZenGuardStatus, openInstagram, setInstagramObservationMode, setInstagramSettings, openX, openYouTube, setObservationMode, setShortsEnabled, setXObservationMode, setXSettings, type ZenGuardStatus } from '@/features/protection/native';

export type FeedDrawer = 'youtube' | 'instagram' | 'x';
type Props = { feed: FeedDrawer | null; onClose: () => void };
const TITLES = { youtube: 'YouTube', instagram: 'Instagram', x: 'X' };
const HOME_MINUTES = [1, 5, 10, 15, 30];

/** Keep the feed list in place while editing cached rules in a native drawer. */
export function FeedControlsDrawer({ feed, onClose }: Props) {
  const { status, loading, readError, refresh } = useSharedGuardStatus();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  const reduceMotion = useReducedMotion();
  const disabled = busy || loading || !status?.available || Boolean(readError);
  const isX = feed === 'x';
  const isInstagram = feed === 'instagram';
  const observing = isInstagram ? status?.instagramObservationMode : isX ? status?.xObservationMode : status?.observationMode;
  const enabled = isInstagram ? true : isX ? status?.xHomeEnabled || status?.xVideosEnabled : status?.shortsEnabled;
  const requiredMask = status ? (status.xHomeEnabled ? 1 : 0) | (status.xVideosEnabled ? 2 : 0) : 3;
  const detected = status && (isInstagram ? (status.instagramSignalMask & 3) === 3 : isX ? (status.xSignalMask & requiredMask) === requiredMask : status.lastDetectionAt > 0);
  const canStart = detected && status?.serviceEnabled && status.protectionEnabled;

  const run = (action: (fresh: ZenGuardStatus) => Promise<void>) => {
    if (inFlight.current || disabled) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    void (async () => {
      try {
        const fresh = await getZenGuardStatus();
        if (!fresh.available) throw new Error('Feed controls need the Android app.');
        await action(fresh);
        await refresh();
      } catch (cause: unknown) {
        setError(cause instanceof Error ? cause.message : 'Could not change the rule. Try again.');
      } finally {
        inFlight.current = false;
        setBusy(false);
      }
    })();
  };
  const change = (next: Partial<Pick<ZenGuardStatus, 'xHomeEnabled' | 'xVideosEnabled' | 'xHomeMinutes' | 'shortsEnabled' | 'instagramWaitSeconds' | 'instagramReelsMinutes' | 'instagramHomeMinutes' | 'instagramExploreBlocked'>>) => run(async (fresh) => {
    const proposed = { ...fresh, ...next };
    const weaker = (fresh.shortsEnabled && !proposed.shortsEnabled) || (fresh.xHomeEnabled && !proposed.xHomeEnabled) || (fresh.xVideosEnabled && !proposed.xVideosEnabled) || proposed.xHomeMinutes > fresh.xHomeMinutes ||
      proposed.instagramWaitSeconds < fresh.instagramWaitSeconds || proposed.instagramReelsMinutes > fresh.instagramReelsMinutes ||
      proposed.instagramHomeMinutes > fresh.instagramHomeMinutes || (fresh.instagramExploreBlocked && !proposed.instagramExploreBlocked);
    if (weaker && await isChangeBlocked()) throw new Error('Settings are locked. Ask to unlock in the Lock tab.');
    if (isInstagram) await setInstagramSettings(proposed.instagramWaitSeconds, proposed.instagramReelsMinutes, proposed.instagramHomeMinutes, proposed.instagramExploreBlocked);
    else if (isX) await setXSettings(proposed.xHomeEnabled, proposed.xVideosEnabled, proposed.xHomeMinutes);
    else await setShortsEnabled(proposed.shortsEnabled);
  });
  const close = () => { if (!inFlight.current) onClose(); };
  const result = getFeedPresentation(status, isX ? 'xVideos' : 'shorts');

  return (
    <Modal visible={feed !== null} transparent animationType={reduceMotion ? 'none' : 'slide'} onRequestClose={close} onShow={() => { setError(''); void refresh(); }} statusBarTranslucent>
      <View className="flex-1 justify-end bg-black/50">
        <Pressable accessibilityRole="button" accessibilityLabel="Close feed controls" onPress={close} disabled={busy} className="absolute inset-0" />
        <SafeAreaView edges={['bottom']} style={{ maxHeight: '88%' }} className="rounded-t-3xl border-t border-line2 bg-panel px-5 pt-3">
          <View className="mb-3 flex-row items-center justify-between">
            <Text accessibilityRole="header" className="text-[20px] font-semibold text-copy">{TITLES[feed ?? 'youtube']}</Text>
            <Pressable accessibilityRole="button" accessibilityLabel="Close feed controls" disabled={busy} onPress={close} className="min-h-11 min-w-11 items-center justify-center"><Text className="text-[28px] text-muted">×</Text></Pressable>
          </View>
          <ScrollView contentContainerStyle={{ paddingBottom: 20 }}>
            {isX ? <View className="mb-5 gap-3">
              <View className="flex-row items-center justify-between"><Text className="text-[15px] font-semibold text-copy">Home feed</Text><Text className="text-[12px] text-muted">{getFeedPresentation(status, 'xHome').statusLabel}</Text></View>
              <RuleChoice label="X Home rule" limitedLabel="Take breaks" allowedLabel="No breaks" enabled={status?.xHomeEnabled === true} disabled={disabled} onChange={(value) => change({ xHomeEnabled: value })} />
              <Text className="text-[13px] text-muted">Break after</Text>
              <PresetRow label="X Home break interval" values={HOME_MINUTES} selected={status?.xHomeMinutes} suffix="m" disabled={disabled || !status?.xHomeEnabled} onSelect={(value) => change({ xHomeMinutes: value })} />
            </View> : null}
            {isInstagram ? <View className="gap-5">
              <View className="gap-3">
                <Text accessibilityRole="header" className="text-[15px] font-semibold text-copy">Reels</Text>
                <Text className="text-[13px] text-muted">{getFeedPresentation(status, 'reels').statusLabel}</Text>
                <Text className="text-[13px] text-muted">Pause before Reels</Text>
                <PresetRow label="Pause before Reels" values={[15, 30, 60, 120, 300]} selected={status?.instagramWaitSeconds} suffix="s" disabled={disabled} onSelect={(value) => change({ instagramWaitSeconds: value })} />
                <Text className="text-[13px] text-muted">Viewing window</Text>
                <PresetRow label="Reels viewing window" values={[1, 5, 10, 15]} selected={status?.instagramReelsMinutes} suffix="m" disabled={disabled} onSelect={(value) => change({ instagramReelsMinutes: value })} />
              </View>
              <View className="gap-3 border-t border-line pt-4">
                <Text accessibilityRole="header" className="text-[15px] font-semibold text-copy">Home feed</Text>
                <Text className="text-[13px] text-muted">{getFeedPresentation(status, 'home').statusLabel} · Break after</Text>
                <PresetRow label="Instagram Home break interval" values={HOME_MINUTES} selected={status?.instagramHomeMinutes} suffix="m" disabled={disabled} onSelect={(value) => change({ instagramHomeMinutes: value })} />
              </View>
              <View className="gap-3 border-t border-line pt-4">
                <Text accessibilityRole="header" className="text-[15px] font-semibold text-copy">Explore</Text>
                <Text className="text-[13px] text-muted">{getFeedPresentation(status, 'explore').statusLabel}</Text>
                <RuleChoice label="Explore rule" limitedLabel="Block Explore" allowedLabel="Allow Explore" enabled={status?.instagramExploreBlocked === true} disabled={disabled} onChange={(value) => change({ instagramExploreBlocked: value })} />
              </View>
            </View> : <View className="gap-3 border-t border-line pt-4">
              <View className="flex-row items-center justify-between"><Text className="text-[15px] font-semibold text-copy">{isX ? 'Video scrolling' : 'Shorts scrolling'}</Text><Text className="text-[12px] text-muted">{result.statusLabel}</Text></View>
              <RuleChoice label={isX ? 'X video rule' : 'YouTube Shorts rule'} limitedLabel="Limit to one" allowedLabel="Allow scrolling" enabled={(isX ? status?.xVideosEnabled : status?.shortsEnabled) === true} disabled={disabled} onChange={(value) => change(isX ? { xVideosEnabled: value } : { shortsEnabled: value })} />
              <Text className="text-[13px] text-muted">{(isX ? status?.xVideosEnabled : status?.shortsEnabled) ? `Watch one ${isX ? 'video' : 'Short'} per visit. Scrolling to another is blocked.` : 'Scrolling has no feed limit.'}</Text>
            </View>}
            {observing && enabled ? <View className="mt-5 gap-2 border-t border-line pt-4">
              {isInstagram ? <Text className="text-[13px] text-muted">{detected ? '✓ Messages and Reels detected' : 'Open Direct Messages, then one Reel from a message. Return here when done.'}</Text> : isX ? <>
                {status?.xHomeEnabled ? <Text className="text-[13px] text-muted">{status.xSignalMask & 1 ? '✓ Home feed detected' : '○ Open the Home feed'}</Text> : null}
                {status?.xVideosEnabled ? <Text className="text-[13px] text-muted">{status.xSignalMask & 2 ? '✓ Video viewer detected' : '○ Open one video'}</Text> : null}
              </> : <Text className="text-[13px] text-muted">{detected ? '✓ Shorts detected' : 'Open one Short, then return here.'}</Text>}
              {canStart ? <PrimaryButton title="Start protection" disabled={disabled} onPress={() => run(async () => { if (isInstagram) await setInstagramObservationMode(false); else if (isX) await setXObservationMode(false); else await setObservationMode(false); })} /> : detected ? <Text className="text-[13px] text-muted">Turn on protection and Android access in Settings.</Text> : null}
            </View> : null}
            <SecondaryButton className="mt-5" title={isInstagram ? 'Open Instagram' : isX ? 'Open X' : 'Open YouTube'} disabled={disabled} onPress={() => run(async () => { if (isInstagram) await openInstagram(); else if (isX) await openX(); else await openYouTube(); })} />
            <ErrorNote message={error || readError} />
          </ScrollView>
        </SafeAreaView>
      </View>
    </Modal>
  );
}

function RuleChoice({ label, limitedLabel, allowedLabel, enabled, disabled, onChange }: { label: string; limitedLabel: string; allowedLabel: string; enabled: boolean; disabled: boolean; onChange: (enabled: boolean) => void }) {
  return <View accessibilityRole="radiogroup" accessibilityLabel={label} className="flex-row gap-2">
    {[true, false].map((value) => <Pressable key={String(value)} accessibilityRole="radio" accessibilityState={{ checked: enabled === value, disabled }} disabled={disabled} onPress={() => onChange(value)} className={`min-h-12 flex-1 items-center justify-center rounded-xl border px-2 ${enabled === value ? 'border-accent bg-accent' : 'border-line2 bg-panel2'} ${disabled ? 'opacity-50' : 'active:opacity-70'}`}><Text className={`text-[14px] font-semibold ${enabled === value ? 'text-onAccent' : 'text-copy'}`}>{value ? limitedLabel : allowedLabel}</Text></Pressable>)}
  </View>;
}

function PresetRow({ label, values, selected, suffix, disabled, onSelect }: { label: string; values: readonly number[]; selected?: number; suffix: string; disabled: boolean; onSelect: (value: number) => void }) {
  return <View accessibilityRole="radiogroup" accessibilityLabel={label} className="flex-row flex-wrap gap-1.5">
    {values.map((value) => <Pressable key={value} accessibilityRole="radio" accessibilityState={{ checked: selected === value, disabled }} disabled={disabled} onPress={() => onSelect(value)} className={`min-h-11 min-w-[52px] flex-1 items-center justify-center rounded-xl border px-2 ${selected === value ? 'border-accent bg-accent' : 'border-line2 bg-panel2'} ${disabled ? 'opacity-40' : 'active:opacity-70'}`}><Text className={`text-[13px] font-semibold ${selected === value ? 'text-onAccent' : 'text-copy'}`}>{value}{suffix}</Text></Pressable>)}
  </View>;
}
