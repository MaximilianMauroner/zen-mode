import { createContext, type ReactNode, useContext, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import { getZenGuardStatus, type ZenGuardStatus } from './native';
import { createProtectionRead, type ProtectionRead } from './protection-reads';
import { onResume, useProtectionRead } from './use-protection-read';

const GuardStatusContext = createContext<ProtectionRead<ZenGuardStatus> | null>(null);

/** Settings, the control tabs, and their header share one native status read. */
export function GuardStatusProvider({ children }: { children: ReactNode }) {
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
  return <GuardStatusContext value={owner}>{children}</GuardStatusContext>;
}

function useGuardStatusOwner() {
  const owner = useContext(GuardStatusContext);
  if (!owner) throw new Error('GuardStatusProvider is required for guard status.');
  return owner;
}

export function useSharedGuardStatus() {
  return useProtectionRead(useGuardStatusOwner());
}

/** Read on focus, every 30 seconds, and on return from Android settings while this screen is focused. */
export function useGuardStatusFocus() {
  useFocusEffect(useGuardStatusOwner().watch);
}
