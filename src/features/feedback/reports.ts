import type { Constants } from 'expo-constants';
import type { Platform } from 'react-native';

export type ReportKind = 'bug' | 'feature';
export type ReportDraft = { kind: ReportKind; message: string; details: string; contactEmail: string };
/** Why the reports inbox refused a report. The screen shows its own text for each reason, never the server's words. */
export type ReportRejection = 'unknown_app' | 'invalid_kind' | 'empty_message' | 'message_too_long' | 'details_too_long' | 'invalid_email' | 'unknown';
export type ReportOutcome =
  | { kind: 'sent' }
  | { kind: 'offline' }
  | { kind: 'timeout' }
  | { kind: 'rate-limited' }
  | { kind: 'rejected'; reason: ReportRejection }
  | { kind: 'failed' };
/** The Expo objects the version line is read from. Only the version, build, and OS fields leave the device. */
export type ReportDevice = { constants: Pick<Constants, 'expoConfig'>; platform: Platform };

export const REPORT_PAGE = '/report';
export const REPORT_LIMIT = 4000;
export const EMAIL_LIMIT = 254;
const TIMEOUT_MS = 15_000;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const REJECTIONS: readonly string[] = ['unknown_app', 'invalid_kind', 'empty_message', 'message_too_long', 'details_too_long', 'invalid_email'];

/** The same checks the inbox makes, so the person sees the reason before anything leaves the device. */
export function checkReport(draft: ReportDraft): ReportRejection | null {
  const message = draft.message.trim();
  if (!message) return 'empty_message';
  if (message.length > REPORT_LIMIT) return 'message_too_long';
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

async function post(origin: string, draft: ReportDraft, device: ReportDevice): Promise<ReportOutcome> {
  const refused = checkReport(draft);
  if (refused) return { kind: 'rejected', reason: refused };
  const body = {
    app: 'zen-mode',
    kind: draft.kind,
    message: draft.message.trim(),
    details: draft.details.trim() || undefined,
    page: REPORT_PAGE,
    context: versionLine(device),
    contactEmail: draft.contactEmail.trim() || undefined,
  };
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(`${origin}/api/reports`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: abort.signal });
  } catch {
    return { kind: abort.signal.aborted ? 'timeout' : 'offline' };
  } finally {
    clearTimeout(timer);
  }
  if (response.ok) return { kind: 'sent' };
  if (response.status === 429) return { kind: 'rate-limited' };
  if (response.status === 400) {
    const reason: unknown = await response.json().then((json: { reason?: unknown } | null) => json?.reason, () => undefined);
    return { kind: 'rejected', reason: typeof reason === 'string' && REJECTIONS.includes(reason) ? reason as ReportRejection : 'unknown' };
  }
  return { kind: 'failed' };
}

const origin = process.env.EXPO_PUBLIC_REPORTS_ORIGIN;

/** Null when this build has no reports inbox, so development builds and the web preview never write into the real one. */
export const sendReport = origin ? (draft: ReportDraft, device: ReportDevice) => post(origin, draft, device) : null;
