import { createContext, type ReactNode, useContext } from 'react';
import { useGuardStatus } from './use-guard-status';

const GuardStatusContext = createContext<ReturnType<typeof useGuardStatus> | null>(null);

/** The fixed summary and feed controls share one current native status read. */
export function GuardStatusProvider({ children }: { children: ReactNode }) {
  const guard = useGuardStatus();
  return <GuardStatusContext value={guard}>{children}</GuardStatusContext>;
}

export function useSharedGuardStatus() {
  const guard = useContext(GuardStatusContext);
  if (!guard) throw new Error('GuardStatusProvider is required for control tabs.');
  return guard;
}
