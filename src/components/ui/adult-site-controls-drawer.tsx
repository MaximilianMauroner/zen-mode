import { useCallback, useRef, useState } from 'react';
import { KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, Switch, Text, TextInput, View } from 'react-native';
import { router } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useReducedMotion } from 'react-native-reanimated';

import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { SectionLabel } from '@/components/ui/card';
import { ErrorNote, SheetHeader } from '@/components/ui/screen';
import { authorizeWeakening } from '@/features/protection/authorize-weakening';
import { useWeakeningGate, WeakeningGateDialog } from './weakening-gate';
import {
  addBlockedDomain,
  getAdultSiteSettings,
  openBrowserCheck,
  removeBlockedDomain,
  setAdultSiteBlockingEnabled,
  type AdultSiteSettings,
} from '@/features/protection/native';
import { useSharedGuardStatus } from '@/features/protection/guard-status-context';
import { colors } from '@/theme/colors';
import { metrics } from '@/theme/metrics';
import { X } from 'lucide-react-native';
import { getBrowserReadiness, getSupportedBrowserAvailability, SUPPORTED_BROWSERS } from '@/features/protection/target-availability';

type Props = { visible: boolean; onClose: () => void };
const BROWSERS = SUPPORTED_BROWSERS;

/** Website rules stay in a drawer so the three-tab control surface does not grow. */
export function AdultSiteControlsDrawer({ visible, onClose }: Props) {
  const { status, refresh: refreshStatus } = useSharedGuardStatus();
  const [settings, setSettings] = useState<AdultSiteSettings | null>(null);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [lockBlocked, setLockBlocked] = useState(false);
  const inFlight = useRef(false);
  const reduceMotion = useReducedMotion();
  const gate = useWeakeningGate();

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setSettings(await getAdultSiteSettings());
    } catch {
      setSettings(null);
      setError('The site rules could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, []);

  const run = (task: () => Promise<void>, fallback: string) => {
    if (inFlight.current || loading || !settings?.available) return;
    inFlight.current = true;
    setBusy(true);
    setError('');
    setLockBlocked(false);
    void (async () => {
      try {
        await task();
        await Promise.all([load(), refreshStatus()]);
      } catch (cause: unknown) {
        const message = cause instanceof Error ? cause.message : fallback;
        if (message.includes('locked')) setLockBlocked(true);
        setError(message);
      } finally {
        inFlight.current = false;
        setBusy(false);
      }
    })();
  };

  const changeEnabled = (enabled: boolean) => run(async () => {
    const fresh = await getAdultSiteSettings();
    if (!await authorizeWeakening(fresh.enabled && !enabled, 'disable adult-site blocking', gate.request)) return;
    if (fresh.enabled && !enabled && (await getAdultSiteSettings()).enabled !== fresh.enabled) throw new Error('The site rule changed. Review it and try again.');
    await setAdultSiteBlockingEnabled(enabled);
  }, 'The site rule could not be changed.');

  const add = () => {
    const value = input;
    if (!value.trim()) {
      setError('Enter a domain like example.com.');
      return;
    }
    run(async () => {
      await addBlockedDomain(value);
      setInput('');
    }, 'That site could not be added.');
  };

  const remove = (host: string) => run(async () => {
    if (!await authorizeWeakening(true, `remove the block for ${host}`, gate.request)) return;
    if (!(await getAdultSiteSettings()).customHosts.includes(host)) throw new Error('The site rule changed. Review it and try again.');
    await removeBlockedDomain(host);
  }, 'That site could not be removed.');

  const close = () => { if (!inFlight.current) onClose(); };
  const disabled = busy || loading || !settings?.available;

  return (
    <Modal
      visible={visible}
      transparent
      animationType={reduceMotion ? 'none' : 'slide'}
      onRequestClose={() => { if (gate.session) gate.finish(false); else close(); }}
      onShow={() => { setInput(''); setLockBlocked(false); void load(); }}
      statusBarTranslucent>
      {/* The embedded gate owns Android keyboard avoidance while it is open. */}
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        enabled={Platform.OS !== 'android' || !gate.session}
        style={{ flex: 1 }}
        className="justify-end bg-black/50">
        <Pressable accessibilityRole="button" accessibilityLabel="Close site controls" onPress={close} disabled={busy} className="absolute inset-0" />
        <SafeAreaView edges={['bottom']} style={{ maxHeight: '88%' }} className="rounded-t-3xl border-t border-line2 bg-panel pt-3">
          <SheetHeader title="Sites" closeLabel="Close site controls" disabled={busy} onClose={close} />
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ paddingHorizontal: 20, paddingBottom: 20 }}>
            <View className="min-h-14 flex-row items-center justify-between gap-4 rounded-3xl border border-line bg-panel2 px-4 py-3">
              <View className="flex-1">
                <Text className="text-item font-semibold text-copy">Block adult sites</Text>
                <Text accessibilityLiveRegion="polite" className="mt-0.5 text-support text-muted">
                  {settings?.enabled ? 'Built-in list plus sites you add.' : 'No website rule is running.'}
                </Text>
              </View>
              <Switch
                accessibilityLabel="Block adult sites"
                accessibilityState={{ checked: settings?.enabled === true, disabled }}
                disabled={disabled}
                onValueChange={changeEnabled}
                trackColor={{ false: colors.track, true: colors.accent }}
                thumbColor={settings?.enabled ? colors.onAccent : colors.copy}
                value={settings?.enabled === true}
              />
            </View>

            <SectionLabel className="mb-2 mt-5">Browsers</SectionLabel>
            <View className="overflow-hidden rounded-3xl border border-line bg-panel2 px-4">
              {BROWSERS.map((browser, index) => {
                const availability = getSupportedBrowserAvailability(status, browser.key);
                const ready = availability === 'installed' && Boolean((settings?.browserSignalMask ?? 0) & browser.mask);
                const statusText = availability === 'absent' ? 'Not installed' : availability === 'disabled' ? 'Disabled' : availability === 'unknown' ? 'Unknown' : ready ? 'Ready' : 'Check';
                const detail = availability === 'absent'
                  ? 'Not installed on this device.'
                  : availability === 'disabled'
                    ? 'Disabled in Android.'
                    : availability === 'unknown'
                      ? 'Availability could not be confirmed.'
                      : ready ? 'Address-bar signal recorded.' : `Open ${browser.label} once, then return here.`;
                return (
                  <View key={browser.label} className={`min-h-14 flex-row items-center justify-between py-3 ${index ? 'border-t border-line' : ''}`}>
                    <View className="flex-1 pr-3">
                      <Text className="text-body font-semibold text-copy">{browser.label}</Text>
                      <Text className="mt-0.5 text-caption text-muted">{detail}</Text>
                    </View>
                    <Text className={`text-caption font-semibold ${ready ? 'text-accent' : availability === 'absent' || availability === 'disabled' ? 'text-muted' : 'text-faint'}`}>{statusText}</Text>
                  </View>
                );
              })}
            </View>
            <Text className="mt-2 text-caption text-muted">
              Zen Mode guards browsers whose address bar it can read. Other browsers and in-app pages stay open.
            </Text>

            <SectionLabel className="mb-2 mt-5">Your sites</SectionLabel>
            <View className="flex-row items-center gap-2">
              <TextInput
                accessibilityLabel="Domain to block"
                autoCapitalize="none"
                autoCorrect={false}
                className="min-h-[48px] flex-1 rounded-2xl border border-line2 bg-night px-4 text-body text-copy"
                editable={!disabled}
                keyboardType="url"
                onChangeText={setInput}
                onSubmitEditing={add}
                placeholder="example.com"
                placeholderTextColor={colors.faint}
                returnKeyType="done"
                value={input}
              />
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Add blocked domain"
                accessibilityState={{ disabled: disabled || !input.trim() }}
                className={`min-h-[48px] min-w-[48px] items-center justify-center rounded-2xl bg-accent px-5 ${disabled || !input.trim() ? 'opacity-40' : 'active:opacity-80'}`}
                disabled={disabled || !input.trim()}
                onPress={add}>
                <Text className="text-item font-bold text-onAccent">Add</Text>
              </Pressable>
            </View>
            <Text className="mt-2 text-caption text-muted">A domain includes all of its subdomains. Visited addresses are never saved.</Text>

            {settings?.customHosts.length ? (
              <View className="mt-3 overflow-hidden rounded-3xl border border-line bg-panel2 px-4">
                {settings.customHosts.map((host, index) => (
                  <View key={host} className={`min-h-14 flex-row items-center ${index ? 'border-t border-line' : ''}`}>
                    <Text className="flex-1 py-3 pr-3 text-body font-medium text-copy">{host}</Text>
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`Remove ${host}`}
                      accessibilityState={{ disabled }}
                      style={{ minHeight: metrics.touchTarget, minWidth: metrics.touchTarget }}
                      className={`-mr-3 items-center justify-center ${disabled ? 'opacity-40' : 'active:opacity-60'}`}
                      disabled={disabled}
                      onPress={() => remove(host)}>
                      <X color={colors.muted} size={20} />
                    </Pressable>
                  </View>
                ))}
              </View>
            ) : settings ? (
              <Text className="mt-3 text-support text-muted">No sites added. The built-in list still applies.</Text>
            ) : null}

            <SecondaryButton className="mt-5" title="Open default browser to check" disabled={disabled || !settings?.enabled || !status?.serviceEnabled || !status.protectionEnabled || getBrowserReadiness(status) === 'none-installed' || getBrowserReadiness(status) === 'disabled'} onPress={() => run(openBrowserCheck, 'The default browser could not be opened. Check that a browser is installed.')} />
            {!settings?.enabled ? <Text className="mt-2 text-caption text-muted">Turn on website blocking before checking a browser.</Text> : !status?.serviceEnabled || !status?.protectionEnabled ? <Text className="mt-2 text-caption text-muted">Turn on Android access and Protection in Settings before checking a browser.</Text> : getBrowserReadiness(status) === 'none-installed' ? <Text className="mt-2 text-caption text-muted">Install one of the supported browsers above before checking a browser.</Text> : getBrowserReadiness(status) === 'disabled' ? <Text className="mt-2 text-caption text-muted">Enable one of the supported browsers above in Android before checking a browser.</Text> : <Text className="mt-2 text-caption text-muted">This opens the default browser at a safe example page. The rows above explain which supported browser still needs its own check.</Text>}
            <Text className="mt-4 text-center text-caption text-faint">The built-in list ships with Zen Mode and is not shown here.</Text>
            {error ? (
              <View className="mt-4 gap-3">
                <ErrorNote message={error} />
                {lockBlocked ? <SecondaryButton title="Manage lock" disabled={busy} onPress={() => { close(); router.navigate('/lock'); }} /> : null}
                {!settings ? <PrimaryButton title="Try again" disabled={loading || busy} onPress={() => void load()} /> : null}
              </View>
            ) : null}
          </ScrollView>
        </SafeAreaView>
        <WeakeningGateDialog gate={gate} embedded />
      </KeyboardAvoidingView>
    </Modal>
  );
}
