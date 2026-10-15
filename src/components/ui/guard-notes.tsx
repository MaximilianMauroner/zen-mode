import { View } from 'react-native';

import { getGuardNotes, type ActionState, type ReadState } from '@/features/protection/control-state';
import { ErrorNote } from './screen';

/** The only renderer of read and action failures. Both channels are required. */
export function GuardNotes({ read, action, className = '' }: { read: ReadState<unknown>; action: ActionState; className?: string }) {
  const notes = getGuardNotes(read, action);
  if (notes.length === 0) return null;
  return (
    <View className={`gap-2 ${className}`}>
      {notes.map(({ channel, message }) => <ErrorNote key={channel} message={message} />)}
    </View>
  );
}
