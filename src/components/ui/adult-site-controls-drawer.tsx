import { useCallback, useRef, useState } from 'react';
import { KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, Switch, Text, TextInput, View } from 'react-native';
import { router } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useReducedMotion } from 'react-native-reanimated';

import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { SectionLabel } from '@/components/ui/card';
import { ErrorNote } from '@/components/ui/screen';
import { isChangeBlocked } from '@/features/protection/lock';
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
        setError(cause instanceof Error ? cause.message : fallback);
      } finally {
        inFlight.current = false;
        setBusy(false);
      }
    })();
  };

  const changeEnabled = (enabled: boolean) => run(async () => {
    const fresh = await getAdultSiteSettings();
    if (fresh.enabled && !enabled && await isChangeBlocked()) {
      setLockBlocked(true);
      throw new Error('Settings are locked. Ask to unlock before allowing adult sites.');
    }
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
    if (await isChangeBlocked()) {
      setLockBlocked(true);
      throw new Error('Removing a site loosens the guard. Ask to unlock, then wait a day.');
    }
    await removeBlockedDomain(host);
  }, 'That site could not be removed.');

  const close = () => { if (!inFlight.current) onClose(); };
  const disabled = busy || loading || !settings?.available;

  return (
    <Modal
      visible={visible}
      transparent
      animationType={reduceMotion ? 'none' : 'slide'}
      onRequestClose={close}
      onShow={() => { setInput(''); setLockBlocked(false); void load(); }}
      statusBarTranslucent>
      <KeyboardAvoidingView className="flex-1 justify-end bg-black/50" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable accessibilityRole="button" accessibilityLabel="Close site controls" onPress={close} disabled={busy} className="absolute inset-0" />
        <SafeAreaView edges={['bottom']} style={{ maxHeight: '88%' }} className="rounded-t-3xl border-t border-line2 bg-panel px-5 pt-3">
          <View className="mb-3 flex-row items-center justify-between">
            <Text accessibilityRole="header" className="text-[20px] font-semibold text-copy">Sites</Text>
            <Pressable accessibilityRole="button" accessibilityLabel="Close site controls" disabled={busy} onPress={close} className="min-h-11 min-w-11 items-center justify-center">
              <Text className="text-[28px] text-muted">×</Text>
            </Pressable>
          </View>
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ paddingBottom: 20 }}>
            <View className="flex-row items-center justify-between gap-4 rounded-2xl border border-line bg-panel2 px-4 py-3.5">
              <View className="flex-1">
                <Text className="text-[15px] font-semibold text-copy">Block adult sites</Text>
                <Text accessibilityLiveRegion="polite" className="mt-0.5 text-[13px] leading-[18px] text-muted">
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

            <SectionLabel className="mb-2 mt-5">BROWSERS</SectionLabel>
            <View className="overflow-hidden rounded-2xl border border-line bg-panel2 px-4">
              {BROWSERS.map((browser, index) => {
                const availability = getSupportedBrowserAvailability(status, browser.key);
                const ready = availability === 'installed' && Boolean((settings?.browserSignalMask ?? 0) & browser.mask);
                const statusText = availability === 'absent' ? 'NOT INSTALLED' : availability === 'disabled' ? 'DISABLED' : availability === 'unknown' ? 'UNKNOWN' : ready ? 'READY' : 'CHECK';
                const detail = availability === 'absent'
                  ? 'Not installed on this device.'
                  : availability === 'disabled'
                    ? 'Disabled in Android.'
                    : availability === 'unknown'
                      ? 'Availability could not be confirmed.'
                      : ready ? 'Address-bar signal recorded.' : `Open ${browser.label} once, then return here.`;
                return (
                  <View key={browser.label} className={`flex-row items-center justify-between py-3.5 ${index ? 'border-t border-line' : ''}`}>
                    <View className="flex-1 pr-3">
                      <Text className="text-[14px] font-semibold text-copy">{browser.label}</Text>
                      <Text className="mt-0.5 text-[12px] text-muted">{detail}</Text>
                    </View>
                    <Text className={`text-[11px] font-bold ${ready ? 'text-accent' : availability === 'absent' || availability === 'disabled' ? 'text-muted' : 'text-faint'}`}>{statusText}</Text>
                  </View>
                );
              })}
            </View>
            <Text className="mt-2 text-[12px] leading-[18px] text-muted">
              Zen Mode guards browsers whose address bar it can read. Other browsers and in-app pages stay open.
            </Text>

            <SectionLabel className="mb-2 mt-5">YOUR SITES</SectionLabel>
            <View className="flex-row items-center gap-2">
              <TextInput
                accessibilityLabel="Domain to block"
                autoCapitalize="none"
                autoCorrect={false}
                className="min-h-12 flex-1 rounded-xl border border-line bg-night px-3.5 text-[14px] text-copy"
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
                className={`min-h-12 items-center justify-center rounded-xl bg-accent px-4 ${disabled || !input.trim() ? 'opacity-40' : 'active:opacity-70'}`}
                disabled={disabled || !input.trim()}
                onPress={add}>
                <Text className="text-[14px] font-bold text-onAccent">Add</Text>
              </Pressable>
            </View>
            <Text className="mt-2 text-[12px] leading-[18px] text-muted">A domain includes all of its subdomains. Visited addresses are never saved.</Text>

            {settings?.customHosts.length ? (
              <View className="mt-3 overflow-hidden rounded-2xl border border-line bg-panel2 px-4">
                {settings.customHosts.map((host, index) => (
                  <View key={host} className={`min-h-14 flex-row items-center ${index ? 'border-t border-line' : ''}`}>
                    <Text className="flex-1 py-3 pr-3 text-[14px] font-medium text-copy">{host}</Text>
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`Remove ${host}`}
                      accessibilityState={{ disabled }}
                      className={`min-h-11 min-w-11 items-center justify-center ${disabled ? 'opacity-40' : 'active:opacity-60'}`}
                      disabled={disabled}
                      onPress={() => remove(host)}>
                      <Text className="text-[24px] text-muted">×</Text>
                    </Pressable>
                  </View>
                ))}
              </View>
            ) : settings ? (
              <Text className="mt-3 text-[13px] text-muted">No sites added. The built-in list still applies.</Text>
            ) : null}

            <SecondaryButton className="mt-5" title="Open default browser to check" disabled={disabled || !settings?.enabled || !status?.serviceEnabled || !status.protectionEnabled || getBrowserReadiness(status) === 'none-installed' || getBrowserReadiness(status) === 'disabled'} onPress={() => run(openBrowserCheck, 'The default browser could not be opened. Check that a browser is installed.')} />
            {!settings?.enabled ? <Text className="mt-2 text-[12px] text-muted">Turn on website blocking before checking a browser.</Text> : !status?.serviceEnabled || !status?.protectionEnabled ? <Text className="mt-2 text-[12px] text-muted">Turn on Android access and Protection in Settings before checking a browser.</Text> : getBrowserReadiness(status) === 'none-installed' ? <Text className="mt-2 text-[12px] text-muted">Install one of the supported browsers above before checking a browser.</Text> : getBrowserReadiness(status) === 'disabled' ? <Text className="mt-2 text-[12px] text-muted">Enable one of the supported browsers above in Android before checking a browser.</Text> : <Text className="mt-2 text-[12px] leading-[18px] text-muted">This opens the default browser at a safe example page. The rows above explain which supported browser still needs its own check.</Text>}
            <Text className="mt-4 text-center text-[11px] leading-[17px] text-faint">The built-in list ships with Zen Mode and is not shown here.</Text>
            <ErrorNote message={error} />
            {lockBlocked ? <SecondaryButton title="Manage lock" disabled={busy} onPress={() => { close(); router.navigate('/lock'); }} /> : null}
            {!settings && error ? <PrimaryButton title="Try again" disabled={loading || busy} onPress={() => void load()} /> : null}
          </ScrollView>
        </SafeAreaView>
      </KeyboardAvoidingView>
    </Modal>
  );
}
