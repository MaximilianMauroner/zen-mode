import { readLockRecord, requirePasscode } from './lock';
import { stateOf } from './lock-policy';
import type { RequestWeakeningPrompt } from './weakening-challenge';

/** One entry point for every change the existing settings lock calls weaker. */
export async function authorizeWeakening(
  isWeaker: boolean,
  action: string,
  request: RequestWeakeningPrompt,
): Promise<boolean> {
  if (!isWeaker) return true;

  const record = await readLockRecord();
  if (record?.version === 2) {
    const answer = await request({ kind: 'passcode', action });
    if (typeof answer !== 'string') return false;
    await requirePasscode(record, answer);
    const current = await readLockRecord();
    if (current?.version !== 2 || current.salt !== record.salt || current.hash !== record.hash) {
      throw new Error('The passcode lock changed. Try again.');
    }
    return true;
  }

  if (stateOf(record, Date.now()).kind !== 'open') throw new Error('Settings are locked. Ask to unlock in the Lock tab.');
  if (await request({ kind: 'challenge', action }) !== true) return false;
  if (stateOf(await readLockRecord(), Date.now()).kind !== 'open') {
    throw new Error('Settings became locked. The change was not saved.');
  }
  return true;
}
