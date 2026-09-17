/** Add a useful platform error while retaining a stable contextual fallback. */
export function getActionError(cause: unknown, fallback: string): string {
  if (cause instanceof Error && cause.message.trim()) return `${fallback} ${cause.message}`;
  return fallback;
}
