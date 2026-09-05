import { useCallback, useRef, useState } from 'react';
import { AppState } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { getZenGuardStatus, type ZenGuardStatus } from './native';

/** Read fresh state on focus and on return from Android settings. Keep the last snapshot during refresh; failed reads clear it. */
export function useGuardStatus() {
  const [status, setStatus] = useState<ZenGuardStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [readError, setReadError] = useState('');
  const inFlight = useRef(false);

  const refresh = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    setLoading(true);
    setReadError('');
    try {
      setStatus(await getZenGuardStatus());
    } catch {
      setStatus(null);
      setReadError('Zen Mode could not read the guard. Try again.');
    } finally {
      setLoading(false);
      inFlight.current = false;
    }
  }, []);

  useFocusEffect(useCallback(() => {
    void refresh();
    const listener = AppState.addEventListener('change', (state) => {
      if (state === 'active') void refresh();
    });
    return () => listener.remove();
  }, [refresh]));

  return { status, loading, readError, refresh };
}
