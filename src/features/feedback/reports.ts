import type { Constants } from 'expo-constants';
import type { Platform } from 'react-native';

export type ReportKind = 'bug' | 'feature';
export type ReportDraft = { kind: ReportKind; message: string; details: string; contactEmail: string };
export type ReportRejection = 'unknown_app' | 'invalid_kind' | 'empty_message' | 'message_too_long' | 'details_too_long' | 'invalid_email' | 'unknown';
export type ReportOutcome =
  | { kind: 'sent' }
  | { kind: 'offline' }
  | { kind: 'timeout' }
  | { kind: 'rate-limited' }
  | { kind: 'rejected'; reason: ReportRejection }
  | { kind: 'failed' };
export type ReportDevice = { constants: Pick<Constants, 'expoConfig'>; platform: Platform };

export const REPORT_LIMIT = 4000;
export const EMAIL_LIMIT = 320;
const TIMEOUT_MS = 15_000;
const REJECTIONS: readonly string[] = ['unknown_app', 'invalid_kind', 'empty_message', 'message_too_long', 'details_too_long', 'invalid_email'];
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function checkReport(draft: ReportDraft): ReportRejection | null {
  if (!draft.message.trim()) return 'empty_message';
  if (draft.message.trim().length > REPORT_LIMIT) return 'message_too_long';
  if (draft.details.trim().length > REPORT_LIMIT) return 'details_too_long';
  const email = draft.contactEmail.trim();
  if (email && (email.length > EMAIL_LIMIT || !EMAIL.test(email))) return 'invalid_email';
  return null;
}

function versionLine({ constants, platform }: ReportDevice): string {
  const config = constants.expoConfig;
  const build = platform.OS === 'android' ? config?.android?.versionCode : platform.OS === 'ios' ? config?.ios?.buildNumber : undefined;
  const os = platform.OS === 'android' ? platform.constants.Release : platform.OS === 'ios' ? platform.Version : undefined;
  return `Zen Mode ${config?.version ?? 'unknown'}${build ? ` (${build})` : ''} · ${[platform.OS, os].filter(Boolean).join(' ')}`;
}

export function formatReport(draft: ReportDraft, device: ReportDevice): string {
  return [
    draft.kind === 'bug' ? 'Problem' : 'Idea',
    draft.message.trim(),
    draft.details.trim() && `${draft.kind === 'bug' ? 'How to repeat it' : 'Why it helps'}\n${draft.details.trim()}`,
    draft.contactEmail.trim() && `Email for a reply\n${draft.contactEmail.trim()}`,
    versionLine(device),
  ].filter(Boolean).join('\n\n');
}

async function post(origin: string, draft: ReportDraft, device: ReportDevice): Promise<ReportOutcome> {
  const refused = checkReport(draft);
  if (refused) return { kind: 'rejected', reason: refused };
  const body = {
    app: 'zen-mode',
    kind: draft.kind,
    message: draft.message.trim(),
    details: draft.details.trim() || undefined,
    page: '/report',
    context: versionLine(device),
    contactEmail: draft.contactEmail.trim() || undefined,
  };
  const abort = new AbortController();
  // eslint-disable-next-line no-restricted-syntax -- Bound the HTTP request and rejection body independently of the UI.
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS);
  const deadline = new Promise<never>((_, reject) => {
    abort.signal.addEventListener('abort', () => reject(new Error('Report deadline expired')), { once: true });
  });
  try {
    const response = await Promise.race([
      fetch(`${origin}/api/reports`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), credentials: 'omit', signal: abort.signal }),
      deadline,
    ]);
    if (response.status === 201) return { kind: 'sent' };
    if (response.status === 429) return { kind: 'rate-limited' };
    if (response.status === 400) {
      const reason: unknown = await Promise.race([response.json().then((json: { reason?: unknown } | null) => json?.reason, () => undefined), deadline]);
      return { kind: 'rejected', reason: typeof reason === 'string' && REJECTIONS.includes(reason) ? reason as ReportRejection : 'unknown' };
    }
    return { kind: 'failed' };
  } catch {
    return { kind: abort.signal.aborted ? 'timeout' : 'offline' };
  } finally {
    clearTimeout(timer);
  }
}

const origin = process.env.EXPO_PUBLIC_REPORTS_ORIGIN;

export const sendReport = origin ? (draft: ReportDraft, device: ReportDevice) => post(origin, draft, device) : null;
