import { useCallback, useEffect, useRef, useState } from 'react';
import { KeyboardAvoidingView, Modal, Platform, ScrollView, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useReducedMotion } from 'react-native-reanimated';

import { alternateChallenge, isChallengeConfirmed, startChallengeSession, submitChallengeAnswer, type ChallengeSession } from '@/features/protection/weakening-challenge';
import { colors } from '@/theme/colors';
import { DangerButton, SecondaryButton } from './button';

/** A successful challenge only grants a chance to confirm; it never runs the mutation. */
export function useWeakeningGate() {
  const [session, setSession] = useState<ChallengeSession | null>(null);
  const [action, setAction] = useState('');
  const [answer, setAnswer] = useState('');
  const [feedback, setFeedback] = useState('');
  const resolver = useRef<((confirmed: boolean) => void) | null>(null);

  const finish = useCallback((confirmed: boolean) => {
    const resolve = resolver.current;
    resolver.current = null;
    setSession(null);
    setAnswer('');
    setFeedback('');
    resolve?.(isChallengeConfirmed(session, confirmed));
  }, [session]);
  useEffect(() => () => { resolver.current?.(false); resolver.current = null; }, []);

  const request = useCallback((actionLabel: string): Promise<boolean> => {
    if (resolver.current) return Promise.resolve(false);
    setAction(actionLabel);
    setSession(startChallengeSession());
    setAnswer('');
    setFeedback('');
    return new Promise((resolve) => { resolver.current = resolve; });
  }, []);

  const submit = () => {
    if (!session || session.phase !== 'challenge') return;
    const result = submitChallengeAnswer(session, answer);
    setSession(result.session);
    setAnswer('');
    setFeedback(result.correct ? '' : 'That was not it. Try this new question, or choose a different kind.');
  };
  const alternate = () => {
    if (!session) return;
    setSession(alternateChallenge(session));
    setAnswer('');
    setFeedback('');
  };

  return { session, action, answer, feedback, setAnswer, request, submit, alternate, finish };
}

type Gate = ReturnType<typeof useWeakeningGate>;

/** Use `embedded` inside an existing native drawer Modal to avoid stacking Modals on Android. */
export function WeakeningGateDialog({ gate, embedded = false }: { gate: Gate; embedded?: boolean }) {
  const reduceMotion = useReducedMotion();
  if (!gate.session) return null;
  const session = gate.session;
  const confirming = session.phase === 'confirm';
  const content = (
    <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} className="flex-1 justify-center bg-black/75 px-4">
      <SafeAreaView edges={['top', 'bottom']} className="max-h-full rounded-3xl border border-line2 bg-panel p-5">
        <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ gap: 16 }}>
          <Text accessibilityRole="header" className="text-[20px] font-semibold text-copy">{confirming ? 'Confirm this change' : 'Pause before changing this rule'}</Text>
          <Text className="text-[14px] leading-[21px] text-muted">{confirming
            ? `You completed the challenges. ${gate.action} will happen only if you confirm below.`
            : `${gate.action} weakens a protection. Complete three short questions before choosing whether to continue.`}</Text>
          {confirming ? (
            <>
              <DangerButton title={`Yes, I am sure I want to ${gate.action}.`} onPress={() => gate.finish(true)} />
              <SecondaryButton title="Cancel — keep protection" onPress={() => gate.finish(false)} />
            </>
          ) : (
            <>
              <Text className="text-[13px] font-semibold text-copy">Question {session.step + 1} of {session.kinds.length}</Text>
              <Text accessibilityLiveRegion="polite" className="text-[17px] leading-[25px] text-copy">{session.challenge?.prompt}</Text>
              <TextInput
                accessibilityLabel="Challenge answer"
                className="rounded-2xl border border-line bg-night px-4 py-3.5 text-[17px] text-copy"
                keyboardType="number-pad"
                placeholder="Enter your answer"
                placeholderTextColor={colors.faint}
                value={gate.answer}
                onChangeText={gate.setAnswer}
                onSubmitEditing={gate.submit}
              />
              {gate.feedback ? <Text accessibilityLiveRegion="polite" className="text-[13px] text-muted">{gate.feedback}</Text> : null}
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
