import { Card } from '@/components/ui/card';
import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { ErrorNote, IconTile, Screen, ScreenTitle } from '@/components/ui/screen';
import { hasCompletedSetup, markSetupComplete } from '@/features/protection/setup';
import { openAccessibilitySettings, setInstagramObservationMode, setNativeProtectionEnabled, setObservationMode } from '@/features/protection/native';
import { colors } from '@/theme/colors';
import { Check, Eye, ShieldCheck } from 'lucide-react-native';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';

export default function SetupScreen() {
  const [consented, setConsented] = useState(false);
  const [error, setError] = useState('');
  const [setupReadState, setSetupReadState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [saving, setSaving] = useState(false);
  const [retryCount, setRetryCount] = useState(0);

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

  const retrySetupCheck = () => {
    if (saving || setupReadState === 'loading') return;
    setError('');
    setSetupReadState('loading');
    setRetryCount((count) => count + 1);
  };

  const finishSetup = async () => {
    if (setupReadState !== 'ready' || saving) return;
    setError('');
    if (!consented) return setError('Read the note above and tick the box first.');
    setSaving(true);
    try {
      await setNativeProtectionEnabled(true);
      await setObservationMode(true);
      await setInstagramObservationMode(true);
      await openAccessibilitySettings();
      await markSetupComplete();
      router.replace('/');
    } catch (setupError) {
      setError(setupError instanceof Error ? setupError.message : 'Setup could not finish.');
    } finally {
      setSaving(false);
    }
  };

  const busy = setupReadState === 'loading' || saving;

  return (
    <Screen>
      <IconTile icon={ShieldCheck} />
      <ScreenTitle
        title="Set up the guard"
        description="Zen Mode watches YouTube Shorts, Instagram Reels, Home and Explore, and X Home and videos. Feed rules keep Messages open. Whole-app limits apply to the apps and times you choose."
      />

      <Card className="border-dangerLine">
        <View className="flex-row items-center">
          <Eye color={colors.danger} size={18} />
          <Text className="ml-2.5 text-[15px] font-semibold text-copy">Accessibility access</Text>
        </View>
        <Text className="mt-3.5 text-[14px] leading-[21px] text-muted">
          To recognize guarded feeds, Zen Mode processes what YouTube, Instagram, and X put on screen through Android accessibility. It also checks which app is in the foreground to apply app limits. When you add an app limit or timed visit, it lists launchable apps installed on this device so you can select one.
        </Text>
        <Text className="mt-2.5 text-[14px] leading-[21px] text-muted">
          Zen Mode does not read screen contents from other apps, record what you type, take screenshots, or send screen contents or installed-app inventory anywhere. It stores guard settings and local usage and detection summaries, not screen text.
        </Text>
        <Pressable
          accessibilityRole="checkbox"
          accessibilityState={{ checked: consented, disabled: busy }}
          className={`mt-4 flex-row items-center ${busy ? 'opacity-40' : 'active:opacity-70'}`}
          disabled={busy}
          onPress={() => setConsented((value) => !value)}>
          <View className={`h-6 w-6 items-center justify-center rounded-lg border ${consented ? 'border-accent bg-accent' : 'border-line2 bg-panel2'}`}>
            {consented ? <Check color={colors.onAccent} size={15} strokeWidth={3} /> : null}
          </View>
          <Text className="ml-3 flex-1 text-[14px] font-medium leading-[20px] text-copy">I understand, and I agree to this use.</Text>
        </Pressable>
      </Card>

      <ErrorNote message={error} />
      <SecondaryButton title="Privacy" disabled={busy} onPress={() => router.navigate('/privacy')} />
      <PrimaryButton
        title={setupReadState === 'loading' ? 'Checking…' : saving ? 'Saving…' : setupReadState === 'error' ? 'Try again' : 'Turn on the guard'}
        disabled={busy}
        onPress={setupReadState === 'error' ? retrySetupCheck : finishSetup}
      />
      <Text className="text-center text-[12px] leading-[18px] text-faint">
        Nothing here stops you turning the service off in Android Settings, or uninstalling the app outright.
      </Text>
    </Screen>
  );
}
