import { useCallback, useEffect, useRef, useState } from 'react';
import { KeyboardAvoidingView, Modal, Platform, ScrollView, Text, TextInput, View, type TextInputProps } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useReducedMotion } from 'react-native-reanimated';

import { alternateChallenge, isChallengeConfirmed, startChallengeSession, submitChallengeAnswer, type ChallengeSession, type RequestWeakeningPrompt } from '@/features/protection/weakening-challenge';
import { colors } from '@/theme/colors';
import { DangerButton, SecondaryButton } from './button';

/** A successful challenge only grants a chance to confirm; it never runs the mutation. */
export function useWeakeningGate() {
  const [session, setSession] = useState<ChallengeSession | { phase: 'passcode' } | null>(null);
  const [action, setAction] = useState('');
  const [answer, setAnswer] = useState('');
  const [feedback, setFeedback] = useState('');
  const resolver = useRef<((answer: boolean | string) => void) | null>(null);

  const settle = useCallback((result: boolean | string) => {
    const resolve = resolver.current;
    resolver.current = null;
    setSession(null);
    setAnswer('');
    setFeedback('');
    resolve?.(result);
  }, []);
  useEffect(() => () => { resolver.current?.(false); resolver.current = null; }, []);

  const request = useCallback<RequestWeakeningPrompt>(({ kind, action: actionLabel }) => {
    if (resolver.current) return Promise.resolve(false);
    setAction(actionLabel);
    setSession(kind === 'passcode' ? { phase: 'passcode' } : startChallengeSession());
    setAnswer('');
    setFeedback('');
    return new Promise((resolve) => { resolver.current = resolve; });
  }, []);

  const finish = (confirmed: boolean) => {
    settle(isChallengeConfirmed(session?.phase === 'passcode' ? null : session, confirmed));
  };
  const submit = () => {
    if (session?.phase === 'passcode') {
      if (answer) settle(answer);
      return;
    }
    if (session?.phase !== 'challenge') return;
    const result = submitChallengeAnswer(session, answer);
    setSession(result.session);
    setAnswer('');
    setFeedback(result.correct ? '' : 'That was not it. Try this new question, or choose a different kind.');
  };
  const alternate = () => {
    if (session?.phase !== 'challenge') return;
    setSession(alternateChallenge(session));
    setAnswer('');
    setFeedback('');
  };

  return { session, action, answer, feedback, setAnswer, request, submit, alternate, finish };
}

type Gate = ReturnType<typeof useWeakeningGate>;

export function PasscodeInput({
  label, ...props
}: Pick<TextInputProps, 'value' | 'onChangeText' | 'editable' | 'onSubmitEditing'> & { label: string }) {
  return (
    <TextInput
      {...props}
      accessibilityLabel={label}
      placeholder={label}
      placeholderTextColor={colors.faint}
      className="min-h-[48px] rounded-2xl border border-line2 bg-night px-4 py-3 text-title text-copy"
      secureTextEntry
      autoComplete="off"
      autoCapitalize="none"
      autoCorrect={false}
    />
  );
}

/** Use `embedded` inside an existing native drawer Modal to avoid stacking Modals on Android. */
export function WeakeningGateDialog({ gate, embedded = false }: { gate: Gate; embedded?: boolean }) {
  const reduceMotion = useReducedMotion();
  if (!gate.session) return null;
  const session = gate.session;
  const confirming = session.phase === 'confirm';
  const title = session.phase === 'passcode' ? 'Enter the passcode'
    : confirming ? 'Confirm this change' : 'Pause before changing this rule';
  const description = session.phase === 'passcode' ? `Enter the current passcode to ${gate.action}.`
    : confirming ? `You completed the challenges. ${gate.action} will happen only if you confirm below.`
    : `${gate.action} weakens a protection. Complete three short questions before choosing whether to continue.`;
  const content = (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} className="justify-center bg-black/75 px-4" style={{ flex: 1 }}>
      <SafeAreaView edges={['top', 'bottom']} className="mx-auto max-h-full w-full max-w-xl rounded-3xl border border-line2 bg-panel">
        <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ gap: 16, padding: 20 }}>
          <Text accessibilityRole="header" className="text-headline font-semibold text-copy">{title}</Text>
          <Text className="text-body text-muted">{description}</Text>
          {session.phase === 'passcode' ? (
            <>
              <PasscodeInput label="Current passcode" value={gate.answer} onChangeText={gate.setAnswer} onSubmitEditing={gate.submit} />
              <DangerButton title="Continue" disabled={!gate.answer} onPress={gate.submit} />
              <SecondaryButton title="Cancel" onPress={() => gate.finish(false)} />
            </>
          ) : confirming ? (
            <>
              <DangerButton title={`Yes, I am sure I want to ${gate.action}.`} onPress={() => gate.finish(true)} />
              <SecondaryButton title="Cancel — keep protection" onPress={() => gate.finish(false)} />
            </>
          ) : (
            <>
              <Text className="text-support font-semibold text-muted">Question {session.step + 1} of {session.kinds.length}</Text>
              <Text accessibilityLiveRegion="polite" className="text-title text-copy">{session.challenge?.prompt}</Text>
              <TextInput
                accessibilityLabel="Challenge answer"
                className="min-h-[48px] rounded-2xl border border-line2 bg-night px-4 py-3 text-title text-copy"
                keyboardType="number-pad"
                placeholder="Enter your answer"
                placeholderTextColor={colors.faint}
                value={gate.answer}
                onChangeText={gate.setAnswer}
                onSubmitEditing={gate.submit}
              />
              {gate.feedback ? <Text accessibilityLiveRegion="polite" className="text-support text-danger">{gate.feedback}</Text> : null}
              <DangerButton title="Check answer" disabled={!gate.answer.trim()} onPress={gate.submit} />
              <SecondaryButton title="Try a different kind of question" onPress={gate.alternate} />
              <SecondaryButton title="Cancel — keep protection" onPress={() => gate.finish(false)} />
            </>
          )}
        </ScrollView>
      </SafeAreaView>
    </KeyboardAvoidingView>
  );
  if (embedded) return <View className="absolute inset-0">{content}</View>;
  return <Modal visible transparent animationType={reduceMotion ? 'none' : 'fade'} onRequestClose={() => gate.finish(false)} statusBarTranslucent>{content}</Modal>;
}
