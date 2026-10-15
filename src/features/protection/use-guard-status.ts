import { useState } from 'react';
import { useFocusEffect } from 'expo-router';
import { isReading, readValue } from './control-state';
import { getZenGuardStatus, type ZenGuardStatus } from './native';
import { createProtectionRead } from './protection-reads';
import { onResume, useProtectionRead } from './use-protection-read';

/** Read fresh state on focus, every 30 seconds, and on return from Android settings. Keep the last snapshot during refresh; failed reads clear it. */
export function useGuardStatus() {
  const [owner] = useState(() => createProtectionRead<ZenGuardStatus>({
    read: async () => ({ kind: 'ready', value: await getZenGuardStatus() }),
    failure: 'Zen Mode could not read the guard. Try again.',
    whileWatched: (refresh) => {
      const timer = setInterval(refresh, 30_000);
      const stopResume = onResume(refresh);
      return () => {
        clearInterval(timer);
        stopResume();
      };
    },
  }));
  useFocusEffect(owner.watch);
  const { read, refresh } = useProtectionRead(owner);
  return { status: readValue(read), loading: isReading(read), readError: read.kind === 'failed' ? read.message : '', refresh };
}
