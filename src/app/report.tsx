import { useCallback, useState } from 'react';
import { Platform, Pressable, Share, Text, TextInput, View } from 'react-native';
import Constants from 'expo-constants';
import { router, useFocusEffect } from 'expo-router';

import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { SectionLabel } from '@/components/ui/card';
import { ChoiceCard } from '@/components/ui/choice';
import { ErrorNote, Screen, ScreenHeader } from '@/components/ui/screen';
import { checkReport, EMAIL_LIMIT, formatReport, REPORT_LIMIT, type ReportDraft, type ReportKind, type ReportRejection } from '@/features/feedback/reports';
import { diagnosticsAvailable, getDiagnostics, shareDiagnostics, type DiagnosticRecording } from '@/features/protection/native';
import { colors } from '@/theme/colors';

const FIELDS = {
  bug: { message: { label: 'What happened?', hint: 'What went wrong? What did you expect?' }, details: { label: 'How to repeat it', hint: 'How can the developer make it happen again? (optional)' } },
  feature: { message: { label: 'Your idea', hint: 'What should Zen Mode do that it does not do now?' }, details: { label: 'Why it helps', hint: 'Why would it help you? (optional)' } },
} as const;

const REJECTED = {
  empty_message: 'Write what happened or what you want.',
  message_too_long: 'Make the message shorter.',
  details_too_long: 'Make the details shorter.',
  invalid_email: 'Enter a valid email address, or leave it empty.',
} as const satisfies Record<ReportRejection, string>;

const empty = (kind: ReportKind): ReportDraft => ({ kind, message: '', details: '', contactEmail: '' });

export default function ReportScreen() {
  const [draft, setDraft] = useState<ReportDraft>(() => empty('bug'));
  const [recording, setRecording] = useState<DiagnosticRecording>({ state: 'empty' });
  const [includeRecording, setIncludeRecording] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useFocusEffect(useCallback(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setIncludeRecording(false);
    const refresh = async () => {
      try {
        const result = await getDiagnostics();
        if (!active) return;
        setRecording(result);
        if (result.state === 'recording') timer = setTimeout(refresh, 1000);
      } catch {
        if (active) setError('Could not read the diagnostic recording.');
      }
    };
    if (diagnosticsAvailable) void refresh();
    return () => { active = false; clearTimeout(timer); };
  }, []));

  const edit = (change: Partial<ReportDraft>) => { setDraft({ ...draft, ...change }); setError(null); };
  const submit = async () => {
    if (sharing) return;
    const rejection = checkReport(draft);
    if (rejection) { setError(REJECTED[rejection]); return; }
    setSharing(true);
    setError(null);
    try {
      const text = formatReport(draft, { constants: Constants, platform: Platform });
      if (draft.kind === 'bug' && includeRecording && recording.state === 'complete') {
        await shareDiagnostics(text);
      } else {
        await Share.share({ message: text });
      }
    } catch {
      setError('Could not open the share sheet. Try again.');
    } finally {
      setSharing(false);
    }
  };
  const fields = FIELDS[draft.kind];

  return (
    <Screen>
      <ScreenHeader title="Report a problem or idea" onBack={() => router.back()} />
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-title font-semibold text-copy">What is it about?</Text>
        <View accessibilityRole="radiogroup" className="gap-2">
          <ChoiceCard title="Problem" detail="Something does not work as it should." selected={draft.kind === 'bug'} disabled={sharing} onPress={() => edit({ kind: 'bug' })} />
          <ChoiceCard title="Idea" detail="Something Zen Mode could do for you." selected={draft.kind === 'feature'} disabled={sharing} onPress={() => edit({ kind: 'feature' })} />
        </View>
      </View>
      <View className="gap-4">
        <Field label={fields.message.label} hint={fields.message.hint} value={draft.message} disabled={sharing} onChange={(message) => edit({ message })} />
        <Field label={fields.details.label} hint={fields.details.hint} value={draft.details} disabled={sharing} onChange={(details) => edit({ details })} />
        <Field label="Email for a reply" hint="Optional" value={draft.contactEmail} disabled={sharing} onChange={(contactEmail) => edit({ contactEmail })} email />
        {draft.kind === 'bug' && diagnosticsAvailable ? (
          <View className="gap-3">
            <Pressable accessibilityRole="checkbox" accessibilityLabel="Include diagnostic recording" accessibilityState={{ checked: includeRecording && recording.state === 'complete', disabled: sharing || recording.state !== 'complete' }} disabled={sharing || recording.state !== 'complete'} onPress={() => setIncludeRecording(!includeRecording)} className="min-h-[48px] flex-row items-center gap-3">
              <View className={`h-6 w-6 items-center justify-center rounded border ${includeRecording && recording.state === 'complete' ? 'border-accent bg-accent' : 'border-muted'}`}>
                {includeRecording && recording.state === 'complete' ? <Text className="font-bold text-onAccent">✓</Text> : null}
              </View>
              <Text className="flex-1 text-copy">Include diagnostic recording</Text>
            </Pressable>
            <Text className="text-support text-muted">{recording.state === 'complete' ? 'Review the recording before you share it.' : recording.state === 'recording' ? 'Stop the recording or wait for it to finish.' : 'No recording is saved yet.'}</Text>
            <SecondaryButton title="Record or review diagnostics" disabled={sharing} onPress={() => router.navigate('/diagnostics')} />
          </View>
        ) : null}
        <Text className="text-support text-muted">Choose where to send your report in the share sheet. To contact the developer, use support@lab4code.com. The report includes your text and app and Android versions.</Text>
        <PrimaryButton title={sharing ? 'Opening share sheet…' : 'Share report'} disabled={sharing || !draft.message.trim()} onPress={() => void submit()} />
        {error ? <ErrorNote message={error} /> : null}
      </View>
    </Screen>
  );
}

const PROSE = { multiline: true, textAlignVertical: 'top', maxLength: REPORT_LIMIT, className: 'min-h-[112px]' } as const;
const EMAIL = { keyboardType: 'email-address', autoCapitalize: 'none', autoCorrect: false, autoComplete: 'email', textContentType: 'emailAddress', maxLength: EMAIL_LIMIT, className: 'min-h-[48px]' } as const;

function Field({ label, hint, value, disabled, onChange, email = false }: { label: string; hint: string; value: string; disabled: boolean; onChange: (text: string) => void; email?: boolean }) {
  const { className, ...input } = email ? EMAIL : PROSE;
  return (
    <View className="gap-2">
      <SectionLabel>{label}</SectionLabel>
      <TextInput
        {...input}
        accessibilityLabel={label}
        accessibilityHint={hint}
        accessibilityState={{ disabled }}
        editable={!disabled}
        placeholder={hint}
        placeholderTextColor={colors.faint}
        value={value}
        onChangeText={onChange}
        className={`${className} rounded-2xl border border-line2 bg-night px-4 py-3 text-body text-copy ${disabled ? 'opacity-40' : ''}`}
      />
    </View>
  );
}
