import { View } from 'react-native';

import { getGuardNotes, type ActionState, type ReadInputs } from '@/features/protection/control-state';
import { ErrorNote } from './screen';

export function GuardNotes({ reads, action, className = '' }: { reads: ReadInputs; action: ActionState; className?: string }) {
  const notes = getGuardNotes(reads, action);
  if (notes.length === 0) return null;
  return (
    <View className={`gap-2 ${className}`}>
      {notes.map(({ channel, message }, index) => <ErrorNote key={`${channel}-${index}`} message={message} />)}
    </View>
  );
}
