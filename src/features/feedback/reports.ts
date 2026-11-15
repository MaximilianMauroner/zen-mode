import type { Constants } from 'expo-constants';
import type { Platform } from 'react-native';

export type ReportKind = 'bug' | 'feature';
export type ReportDraft = { kind: ReportKind; message: string; details: string; contactEmail: string };
export type ReportRejection = 'empty_message' | 'message_too_long' | 'details_too_long' | 'invalid_email';
export type ReportDevice = { constants: Pick<Constants, 'expoConfig'>; platform: Platform };

export const REPORT_LIMIT = 4000;
export const EMAIL_LIMIT = 320;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function checkReport(draft: ReportDraft): ReportRejection | null {
  if (!draft.message.trim()) return 'empty_message';
  if (draft.message.trim().length > REPORT_LIMIT) return 'message_too_long';
  if (draft.details.trim().length > REPORT_LIMIT) return 'details_too_long';
  const email = draft.contactEmail.trim();
  if (email && (email.length > EMAIL_LIMIT || !EMAIL.test(email))) return 'invalid_email';
  return null;
}

export function formatReport(draft: ReportDraft, { constants, platform }: ReportDevice): string {
  const config = constants.expoConfig;
  const build = platform.OS === 'android' ? config?.android?.versionCode : platform.OS === 'ios' ? config?.ios?.buildNumber : undefined;
  const os = platform.OS === 'android' ? platform.constants.Release : platform.OS === 'ios' ? platform.Version : undefined;
  const version = `Zen Mode ${config?.version ?? 'unknown'}${build ? ` (${build})` : ''} · ${[platform.OS, os].filter(Boolean).join(' ')}`;
  return [
    draft.kind === 'bug' ? 'Problem' : 'Idea',
    draft.message.trim(),
    draft.details.trim() && `${draft.kind === 'bug' ? 'How to repeat it' : 'Why it helps'}\n${draft.details.trim()}`,
    draft.contactEmail.trim() && `Email for a reply\n${draft.contactEmail.trim()}`,
    version,
  ].filter(Boolean).join('\n\n');
}
