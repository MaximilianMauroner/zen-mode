import { isChangeBlocked } from './lock';

/** One entry point for every change the existing settings lock calls weaker. */
export async function authorizeWeakening(
  isWeaker: boolean,
  action: string,
  requestChallenge: (action: string) => Promise<boolean>,
): Promise<boolean> {
  if (!isWeaker) return true;
  if (await isChangeBlocked()) throw new Error('Settings are locked. Ask to unlock in the Lock tab.');
  if (!await requestChallenge(action)) return false;
  // A lock may have been armed while the user was answering.
  if (await isChangeBlocked()) throw new Error('Settings became locked. The change was not saved.');
  return true;
}
