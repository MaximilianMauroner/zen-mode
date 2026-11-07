import { useState } from 'react';
import { Platform, Text, TextInput, View } from 'react-native';
import Constants from 'expo-constants';
import { Redirect, router } from 'expo-router';

import { PrimaryButton } from '@/components/ui/button';
import { SectionLabel } from '@/components/ui/card';
import { ChoiceCard } from '@/components/ui/choice';
import { ErrorNote, Screen, ScreenHeader } from '@/components/ui/screen';
import { EMAIL_LIMIT, REPORT_LIMIT, sendReport, type ReportDraft, type ReportKind, type ReportOutcome, type ReportRejection } from '@/features/feedback/reports';
import { colors } from '@/theme/colors';

const FIELDS = {
  bug: { message: { label: 'What happened?', hint: 'What went wrong? What did you expect?' }, details: { label: 'How to repeat it', hint: 'How can the developer make it happen again? (optional)' } },
  feature: { message: { label: 'Your idea', hint: 'What should Zen Mode do that it does not do now?' }, details: { label: 'Why it helps', hint: 'Why would it help you? (optional)' } },
} as const;

const REJECTED = {
  unknown_app: 'This version cannot send reports. Update Zen Mode.',
  invalid_kind: 'Choose Problem or Idea.',
  empty_message: 'Write what happened or what you want.',
  message_too_long: 'Make the message shorter.',
  details_too_long: 'Make the details shorter.',
  invalid_email: 'Enter a valid email address, or leave it empty.',
  unknown: 'The report was not accepted. Check the text and try again.',
} as const satisfies Record<ReportRejection, string>;

const FAILED = {
  offline: 'No connection. Check your network and try again.',
  timeout: 'No answer from the server. Try again in a moment.',
  'rate-limited': 'Too many reports. Try again in a few minutes.',
  failed: 'The report was not sent. Try again later.',
} as const satisfies Record<Exclude<ReportOutcome['kind'], 'sent' | 'rejected'>, string>;

const empty = (kind: ReportKind): ReportDraft => ({ kind, message: '', details: '', contactEmail: '' });

export default function ReportScreen() {
  const [draft, setDraft] = useState<ReportDraft>(() => empty('bug'));
  const [sending, setSending] = useState(false);
  const [outcome, setOutcome] = useState<ReportOutcome | null>(null);
  if (!sendReport) return <Redirect href="/settings" />;
  const send = sendReport;
  const edit = (change: Partial<ReportDraft>) => { setDraft({ ...draft, ...change }); setOutcome(null); };
  const submit = async () => {
    if (sending) return;
    setSending(true);
    setOutcome(null);
    const result = await send(draft, { constants: Constants, platform: Platform });
    setSending(false);
    setOutcome(result);
    if (result.kind === 'sent') setDraft(empty(draft.kind));
  };
  const fields = FIELDS[draft.kind];

  return (
    <Screen>
      <ScreenHeader title="Report a problem or idea" onBack={() => router.back()} />
      <View className="gap-3">
        <Text accessibilityRole="header" className="text-title font-semibold text-copy">What is it about?</Text>
        <View accessibilityRole="radiogroup" className="gap-2">
          <ChoiceCard title="Problem" detail="Something does not work as it should." selected={draft.kind === 'bug'} disabled={sending} onPress={() => edit({ kind: 'bug' })} />
          <ChoiceCard title="Idea" detail="Something Zen Mode could do for you." selected={draft.kind === 'feature'} disabled={sending} onPress={() => edit({ kind: 'feature' })} />
        </View>
      </View>
      <View className="gap-4">
        <Field label={fields.message.label} hint={fields.message.hint} value={draft.message} disabled={sending} onChange={(message) => edit({ message })} />
        <Field label={fields.details.label} hint={fields.details.hint} value={draft.details} disabled={sending} onChange={(details) => edit({ details })} />
        <Field label="Email for a reply" hint="Optional" value={draft.contactEmail} disabled={sending} onChange={(contactEmail) => edit({ contactEmail })} email />
        <Text className="text-support text-muted">Sends only what you type, the app version, and the Android version. Never your rules, apps, sites, or usage.</Text>
        <PrimaryButton title={sending ? 'Sending…' : 'Send report'} disabled={sending || !draft.message.trim()} onPress={() => void submit()} />
        {outcome?.kind === 'sent' ? (
          <Text accessibilityLiveRegion="polite" className="text-support font-medium text-accent">Thank you. Your report was sent.</Text>
        ) : outcome ? (
          <ErrorNote message={outcome.kind === 'rejected' ? REJECTED[outcome.reason] : FAILED[outcome.kind]} />
        ) : null}
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
