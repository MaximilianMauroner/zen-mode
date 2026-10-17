/** What a screen knows about one native dataset. A failed read keeps no snapshot. */
export type ReadState<T> =
  | { kind: 'loading' }
  | { kind: 'ready'; value: T; refreshing: boolean }
  | { kind: 'failed'; message: string }
  | { kind: 'android-required' };

export function readValue<T>(read: ReadState<T>): T | null {
  return read.kind === 'ready' ? read.value : null;
}

export function isReading(read: ReadState<unknown>): boolean {
  return read.kind === 'loading' || (read.kind === 'ready' && read.refreshing);
}

/** Feedback from the current editor's last action. A read never changes it. */
export type ActionState =
  | { kind: 'idle' }
  | { kind: 'running' }
  | { kind: 'refused'; message: string }
  | { kind: 'failed'; message: string };

export const IDLE_ACTION: ActionState = { kind: 'idle' };
export const RUNNING_ACTION: ActionState = { kind: 'running' };

/** A settings-lock refusal can offer the Lock tab. Other failures cannot. */
export function actionFailure(cause: unknown, fallback: string): ActionState {
  const message = cause instanceof Error ? cause.message : fallback;
  return message.includes('locked') ? { kind: 'refused', message } : { kind: 'failed', message };
}

export type ReadInputs = readonly [ReadState<unknown>, ...ReadState<unknown>[]];

export type GuardNote = { channel: 'read' | 'action'; message: string };

/** A read failure and an action failure are separate facts, so neither may hide the other. */
export function getGuardNotes(reads: ReadInputs, action: ActionState): GuardNote[] {
  const notes: GuardNote[] = [];
  for (const read of reads) {
    if (read.kind === 'failed') notes.push({ channel: 'read', message: read.message });
  }
  if (action.kind === 'refused' || action.kind === 'failed') notes.push({ channel: 'action', message: action.message });
  return notes;
}
