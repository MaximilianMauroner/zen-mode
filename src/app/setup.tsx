import { Card } from '@/components/ui/card';
import { ChoiceCard } from '@/components/ui/choice';
import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { ErrorNote, IconTile, Screen, ScreenTitle } from '@/components/ui/screen';
import { acceptSetupConsent, confirmAndroidAccess, hasCompletedSetup, markSetupComplete } from '@/features/protection/setup';
import { RECOMMENDED_DEFAULTS_SUMMARY, applyRecommendedDefaults } from '@/features/protection/recommended-defaults';
import { openAccessibilitySettings } from '@/features/protection/native';
import { colors } from '@/theme/colors';
import { Check, Eye, ShieldCheck } from 'lucide-react-native';
import { router } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Platform, Pressable, Text, View } from 'react-native';

type SetupChoice = 'defaults' | 'customize';

export default function SetupScreen() {
  const [consented, setConsented] = useState(false);
  const [error, setError] = useState('');
  const [setupReadState, setSetupReadState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [saving, setSaving] = useState(false);
  const [retryCount, setRetryCount] = useState(0);
  const [choice, setChoice] = useState<SetupChoice | null>(null);
  const [showDetails, setShowDetails] = useState(false);
  const [accessRequested, setAccessRequested] = useState(false);
  const accessRequestedRef = useRef(false);
  const accessCheckInFlight = useRef(false);
  const choiceRef = useRef<SetupChoice | null>(null);

  useEffect(() => {
    let mounted = true;
    void (async () => {
      try {
        if (await hasCompletedSetup()) {
          router.replace('/');
        } else if (mounted) {
          setSetupReadState('ready');
        }
      } catch {
        if (mounted) {
          setSetupReadState('error');
          setError('Could not read your setup. Try again.');
        }
      }
    })();

    return () => {
      mounted = false;
    };
  }, [retryCount]);

  const checkAccess = useCallback(async () => {
    if (!accessRequestedRef.current || accessCheckInFlight.current) return;
    accessCheckInFlight.current = true;
    setSaving(true);
    setError('');
    try {
      await confirmAndroidAccess();
      if (choiceRef.current === 'defaults') await applyRecommendedDefaults();
      await markSetupComplete();
      router.replace(choiceRef.current === 'customize' ? '/?setup=customize' : '/');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not confirm Android access. Try again.');
    } finally {
      setSaving(false);
      accessCheckInFlight.current = false;
    }
  }, []);

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    let mounted = true;
    const listener = AppState.addEventListener('change', (state) => {
      if (mounted && state === 'active') void checkAccess();
    });
    return () => { mounted = false; listener.remove(); };
  }, [checkAccess]);

  const retrySetupCheck = () => {
    if (saving || setupReadState === 'loading') return;
    setError('');
    setSetupReadState('loading');
    setRetryCount((count) => count + 1);
  };

  const openAccess = async () => {
    if (setupReadState !== 'ready' || saving) return;
    setError('');
    if (!choice) return setError('Choose healthy defaults or Customize first.');
    if (!consented) return setError('Read the note above and tick the box first.');
    setSaving(true);
    try {
      await acceptSetupConsent();
      if (Platform.OS === 'android') {
        choiceRef.current = choice;
        accessRequestedRef.current = true;
        setAccessRequested(true);
        await openAccessibilitySettings();
      } else {
        if (choice === 'defaults') await applyRecommendedDefaults();
        await markSetupComplete();
        router.replace('/');
      }
    } catch (setupError) {
      setError(setupError instanceof Error ? setupError.message : 'Setup could not finish.');
    } finally {
      setSaving(false);
    }
  };

  const busy = setupReadState === 'loading' || saving;
  const selectionDisabled = busy || accessRequested;

  return (
    <Screen>
      <IconTile icon={ShieldCheck} />
      <ScreenTitle
        title="Set your boundaries"
        description="Choose a starting point, then grant Android access. Zen Mode checks each supported feed before applying its rule."
      />

      <Card>
        <Text accessibilityRole="header" className="text-item font-semibold text-copy">How would you like to start?</Text>
        <View accessibilityRole="radiogroup" accessibilityLabel="Starting rules" className="mt-3 gap-2">
          <ChoiceCard title="Use healthy defaults" detail="Start with the rules listed below. You can adjust them later." selected={choice === 'defaults'} disabled={selectionDisabled} onPress={() => setChoice('defaults')} />
          <ChoiceCard title="Customize" detail="Open Feeds and Sites after access. Protection stays paused while you choose rules." selected={choice === 'customize'} disabled={selectionDisabled} onPress={() => setChoice('customize')} />
        </View>
        {choice === 'defaults' ? (
          <View className="mt-3 gap-1.5">
            {RECOMMENDED_DEFAULTS_SUMMARY.map((line) => (
              <View key={line} className="flex-row gap-2">
                <Check color={colors.accent} size={15} style={{ marginTop: 2 }} />
                <Text className="flex-1 text-support text-muted">{line}</Text>
              </View>
            ))}
          </View>
        ) : null}
      </Card>

      <Card>
        <View className="flex-row items-center">
          <Eye color={colors.accent} size={18} />
          <Text accessibilityRole="header" className="ml-2.5 text-item font-semibold text-copy">Accessibility access</Text>
        </View>
        <Text className="mt-3 text-body text-muted">
          Zen Mode uses Android accessibility to recognize guarded YouTube, Instagram, and X feeds, read addresses in supported browser bars when site blocking is on, and identify the foreground app for app limits. This access is needed to apply your rules.
        </Text>
        <Text className="mt-2.5 text-body text-muted">
          Screen contents and browser addresses stay on this device. Zen Mode does not record what you type, take screenshots, or send this data anywhere.
        </Text>
        <Pressable accessibilityRole="button" accessibilityState={{ expanded: showDetails, disabled: busy }} disabled={busy} onPress={() => setShowDetails((value) => !value)} className="mt-1 min-h-12 justify-center self-start">
          <Text className="text-support font-semibold text-accent">{showDetails ? 'Hide access details' : 'More about this access'}</Text>
        </Pressable>
        {showDetails ? <Text className="mb-1 text-support text-muted">When you add an app limit or timed visit, Zen Mode lists launchable apps on this device so you can choose one. It stores guard settings and local usage and detection summaries, not visited addresses, screen text, or the installed-app inventory. It does not inspect browser page content.</Text> : null}
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{ checked: consented, disabled: selectionDisabled }}
          className={`mt-3 min-h-12 flex-row items-center rounded-2xl border px-3.5 ${consented ? 'border-accent bg-accentBg' : 'border-line2 bg-panel2'} ${selectionDisabled ? 'opacity-40' : 'active:opacity-70'}`}
          disabled={selectionDisabled}
          onPress={() => setConsented((value) => !value)}>
          <View className={`h-5 w-5 items-center justify-center rounded-md border-2 ${consented ? 'border-accent bg-accent' : 'border-muted'}`}>
            {consented ? <Check color={colors.onAccent} size={14} strokeWidth={3} /> : null}
          </View>
          <Text className="ml-3 flex-1 py-3 text-body font-medium text-copy">I understand, and I agree to this use.</Text>
        </Pressable>
      </Card>

      <ErrorNote message={error} />
      {accessRequested && Platform.OS === 'android' ? <Text className="text-support text-muted">Setup is pending until Zen Mode confirms Android accessibility access. If you did not grant it, open Settings and try again.</Text> : null}
      <View className="gap-3">
        <PrimaryButton
          title={setupReadState === 'loading' ? 'Checking…' : saving ? 'Checking access…' : setupReadState === 'error' ? 'Try again' : accessRequested ? 'Check Android access again' : 'Continue to Android access'}
          disabled={busy}
          onPress={setupReadState === 'error' ? retrySetupCheck : accessRequested ? () => { void checkAccess(); } : openAccess}
        />
        {accessRequested && Platform.OS === 'android' ? <SecondaryButton title="Open Android settings" disabled={busy} onPress={() => { void openAccessibilitySettings().catch((cause: unknown) => setError(cause instanceof Error ? cause.message : 'Could not open Android settings.')); }} /> : null}
        <SecondaryButton title="Privacy" disabled={busy} onPress={() => router.navigate('/privacy')} />
      </View>
      <Text className="text-center text-caption text-faint">
        Nothing here stops you turning the service off in Android Settings, or uninstalling the app outright.
      </Text>
    </Screen>
  );
}
