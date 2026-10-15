import { useState, useSyncExternalStore } from 'react';
import { AppState } from 'react-native';
import { useFocusEffect } from 'expo-router';

import { createProtectionRead, type ProtectionRead, type ReadResult } from './protection-reads';

/** Returning from another app does not refocus the route, so read again when Zen Mode becomes active. */
export function onResume(refresh: () => void) {
  const listener = AppState.addEventListener('change', (state) => {
    if (state === 'active') refresh();
  });
  return () => listener.remove();
}

export function useProtectionRead<T>(owner: ProtectionRead<T>) {
  return { read: useSyncExternalStore(owner.subscribe, owner.getSnapshot, owner.getSnapshot), refresh: owner.refresh };
}

/** A dataset that one screen owns. It reads on focus, and on resume while the screen is focused. */
export function useScreenRead<T>(read: () => Promise<ReadResult<T>>, failure: string) {
  const [owner] = useState(() => createProtectionRead({ read, failure, whileWatched: onResume }));
  useFocusEffect(owner.watch);
  return useProtectionRead(owner);
}
