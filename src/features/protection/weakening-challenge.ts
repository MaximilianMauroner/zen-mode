/** Local, bounded challenges used only after the settings lock permits a weaker rule. */
export type ChallengeKind = 'arithmetic' | 'ordering' | 'attention';
export type Challenge = { kind: ChallengeKind; prompt: string; answer: string };
export type ChallengeSession = {
  step: number;
  kinds: ChallengeKind[];
  challenge: Challenge | null;
  phase: 'challenge' | 'confirm';
};

const KINDS: ChallengeKind[] = ['arithmetic', 'ordering', 'attention'];
const pick = (min: number, max: number, random: () => number) => min + Math.floor(random() * (max - min + 1));

export function createChallenge(kind: ChallengeKind, random: () => number = Math.random): Challenge {
  if (kind === 'arithmetic') {
    const first = pick(8, 24, random);
    const added = pick(3, 12, random);
    const removed = pick(2, 9, random);
    return { kind, prompt: `Start with ${first}. Add ${added}, then subtract ${removed}. What is the result?`, answer: String(first + added - removed) };
  }
  if (kind === 'ordering') {
    const first = pick(10, 39, random);
    const second = first + pick(2, 9, random);
    const third = second + pick(2, 9, random);
    const values = [first, second, third];
    for (let index = values.length - 1; index > 0; index--) {
      const swap = pick(0, index, random);
      [values[index], values[swap]] = [values[swap], values[index]];
    }
    return { kind, prompt: `Which number is in the middle when you order ${values.join(', ')} from smallest to largest?`, answer: String(second) };
  }
  const signal = pick(2, 9, random);
  const low = pick(2, 15, random);
  const high = low + pick(2, 12, random);
  return {
    kind,
    prompt: `If ${signal} is even, enter the smaller number. If it is odd, enter the larger number. Choices: ${low} and ${high}.`,
    answer: String(signal % 2 === 0 ? low : high),
  };
}

export function startChallengeSession(random: () => number = Math.random): ChallengeSession {
  const kinds = [...KINDS];
  for (let index = kinds.length - 1; index > 0; index--) {
    const swap = pick(0, index, random);
    [kinds[index], kinds[swap]] = [kinds[swap], kinds[index]];
  }
  return { step: 0, kinds, challenge: createChallenge(kinds[0], random), phase: 'challenge' };
}

export function submitChallengeAnswer(session: ChallengeSession, input: string, random: () => number = Math.random): { session: ChallengeSession; correct: boolean } {
  if (session.phase !== 'challenge' || !session.challenge) return { session, correct: false };
  if (input.trim() !== session.challenge.answer) {
    return { session: { ...session, challenge: createChallenge(session.challenge.kind, random) }, correct: false };
  }
  const step = session.step + 1;
  return {
    session: step === session.kinds.length
      ? { ...session, step, challenge: null, phase: 'confirm' }
      : { ...session, step, challenge: createChallenge(session.kinds[step], random) },
    correct: true,
  };
}

/** Replaces an inaccessible question with another family at the same step. */
export function alternateChallenge(session: ChallengeSession, random: () => number = Math.random): ChallengeSession {
  if (session.phase !== 'challenge' || !session.challenge) return session;
  const current = KINDS.indexOf(session.challenge.kind);
  const next = KINDS[(current + 1) % KINDS.length];
  return { ...session, challenge: createChallenge(next, random) };
}

/** The caller may save only after the separate final confirmation. */
export function isChallengeConfirmed(session: ChallengeSession | null, finalChoice: boolean): boolean {
  return finalChoice && session?.phase === 'confirm';
}
