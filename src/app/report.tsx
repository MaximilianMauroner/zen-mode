import { useCallback, useRef, useState } from 'react';
import { Platform, Pressable, Text, TextInput, View } from 'react-native';
import Constants from 'expo-constants';
import { Redirect, router, useFocusEffect } from 'expo-router';

import { PrimaryButton, SecondaryButton } from '@/components/ui/button';
import { SectionLabel } from '@/components/ui/card';
import { ChoiceCard } from '@/components/ui/choice';
import { ErrorNote, Screen, ScreenHeader } from '@/components/ui/screen';
import { checkReport, EMAIL_LIMIT, formatReport, REPORT_LIMIT, sendReport, type ReportDraft, type ReportKind, type ReportOutcome, type ReportRejection } from '@/features/feedback/reports';
import { diagnosticsAvailable, getDiagnostics, shareDiagnostics, type DiagnosticRecording } from '@/features/protection/native';
import { colors } from '@/theme/colors';

const FIELDS = {
  bug: { message: { label: 'What happened?', hint: 'What went wrong? What did you expect?' }, details: { label: 'How to repeat it', hint: 'How can the developer make it happen again? (optional)' } },
  feature: { message: { label: 'Your idea', hint: 'What should Zen Mode do that it does not do now?' }, details: { label: 'Why it helps', hint: 'Why would it help you? (optional)' } },
} as const;

const REJECTED = {
  unknown_app: 'This version cannot send reports. Update Zen Mode.',
  invalid_kind: 'Choose Problem or Idea.',
  unknown: 'The inbox rejected the report. Check the text and try again.',
  empty_message: 'Write what happened or what you want.',
  message_too_long: 'Make the message shorter.',
  details_too_long: 'Make the details shorter.',
  invalid_email: 'Enter a valid email address, or leave it empty.',
} as const satisfies Record<ReportRejection, string>;

const FAILED = {
  offline: 'Could not reach the inbox. Check your connection and try again.',
  timeout: 'The inbox did not respond in time.',
  'rate-limited': 'Too many reports. Try again in a few minutes.',
  failed: 'The report could not be sent. Try again later.',
} as const satisfies Record<Exclude<ReportOutcome['kind'], 'sent' | 'rejected'>, string>;

const empty = (kind: ReportKind): ReportDraft => ({ kind, message: '', details: '', contactEmail: '' });

export default function ReportScreen() {
  const [draft, setDraft] = useState<ReportDraft>(() => empty('bug'));
  const [recording, setRecording] = useState<DiagnosticRecording>({ state: 'empty' });
  const [includeRecording, setIncludeRecording] = useState(false);
  const [action, setAction] = useState<'send' | 'share' | null>(null);
  const busy = useRef(false);
  const [sent, setSent] = useState(false);
  const canSend = Platform.OS === 'android' && sendReport;
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

  if (!canSend && !diagnosticsAvailable) return <Redirect href="/settings" />;

  const edit = (change: Partial<ReportDraft>) => {
    setDraft({ ...draft, ...change });
    setError(null);
    setSent(false);
  };
  const shareRecording = draft.kind === 'bug' && includeRecording && recording.state === 'complete';
  const submit = async (nextAction: 'send' | 'share') => {
    if (busy.current) return;
    const rejection = nextAction === 'share' ? checkReport(draft) : null;
    if (rejection) { setError(REJECTED[rejection]); return; }
    busy.current = true;
    setAction(nextAction);
    setError(null);
    setSent(false);
    const device = { constants: Constants, platform: Platform };
    try {
      if (nextAction === 'share' && shareRecording) {
        await shareDiagnostics(formatReport(draft, device));
      } else if (nextAction === 'send' && canSend) {
        const result = await canSend(draft, device);
        if (result.kind === 'sent') {
          setSent(true);
          setDraft(empty(draft.kind));
        } else {
          setError(result.kind === 'rejected' ? REJECTED[result.reason] : FAILED[result.kind]);
        }
      }
    } catch {
      setError(nextAction === 'share' ? 'Could not open the share sheet. Try again.' : FAILED.failed);
    } finally {
      busy.current = false;
      setAction(null);
    }
  };
  const sendingOrSharing = action !== null;
  const fields = FIELDS[draft.kind];

  return (
    <Screen>
      <ScreenHeader title="Report a problem or idea" onBack={() => router.back()} />
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-title font-semibold text-copy">What is it about?</Text>
        <View accessibilityRole="radiogroup" className="gap-2">
          <ChoiceCard title="Problem" detail="Something does not work as it should." selected={draft.kind === 'bug'} disabled={sendingOrSharing} onPress={() => edit({ kind: 'bug' })} />
          <ChoiceCard title="Idea" detail="Something Zen Mode could do for you." selected={draft.kind === 'feature'} disabled={sendingOrSharing} onPress={() => edit({ kind: 'feature' })} />
        </View>
      </View>
      <View className="gap-4">
        <Field label={fields.message.label} hint={fields.message.hint} value={draft.message} disabled={sendingOrSharing} onChange={(message) => edit({ message })} />
        <Field label={fields.details.label} hint={fields.details.hint} value={draft.details} disabled={sendingOrSharing} onChange={(details) => edit({ details })} />
        <Field label="Email for a reply" hint="Optional" value={draft.contactEmail} disabled={sendingOrSharing} onChange={(contactEmail) => edit({ contactEmail })} email />
        {draft.kind === 'bug' && diagnosticsAvailable ? (
          <View className="gap-3">
            <Pressable accessibilityRole="checkbox" accessibilityLabel="Share a diagnostic recording" accessibilityState={{ checked: includeRecording && recording.state === 'complete', disabled: sendingOrSharing || recording.state !== 'complete' }} disabled={sendingOrSharing || recording.state !== 'complete'} onPress={() => setIncludeRecording(!includeRecording)} className="min-h-[48px] flex-row items-center gap-3">
              <View className={`h-6 w-6 items-center justify-center rounded border ${includeRecording && recording.state === 'complete' ? 'border-accent bg-accent' : 'border-muted'}`}>
                {includeRecording && recording.state === 'complete' ? <Text className="font-bold text-onAccent">✓</Text> : null}
              </View>
              <Text className="flex-1 text-copy">Share a diagnostic recording</Text>
            </Pressable>
            <Text className="text-support text-muted">{recording.state === 'complete' ? 'Review the recording before you share it.' : recording.state === 'recording' ? 'Stop the recording or wait for it to finish.' : 'No recording is saved yet.'}</Text>
            <SecondaryButton title="Record or review diagnostics" disabled={sendingOrSharing} onPress={() => router.navigate('/diagnostics')} />
          </View>
        ) : null}
        {canSend ? (
          <>
            <Text className="text-support text-muted">Send report delivers your text, optional reply email, and app and Android versions to the Lab4Code inbox.</Text>
            <PrimaryButton title={action === 'send' ? 'Sending…' : 'Send report'} disabled={sendingOrSharing || !draft.message.trim()} onPress={() => void submit('send')} />
          </>
        ) : null}
        {shareRecording ? (
          <>
            <Text className="text-support text-muted">Share the recording and report through an app of your choice. To reach Lab4Code by email, use support@lab4code.com.</Text>
            <SecondaryButton title={action === 'share' ? 'Opening share sheet…' : 'Share report with recording'} disabled={sendingOrSharing || !draft.message.trim()} onPress={() => void submit('share')} />
          </>
        ) : null}
        {sent ? <Text accessibilityLiveRegion="polite" className="text-support font-medium text-accent">Your report was sent.</Text> : null}
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
