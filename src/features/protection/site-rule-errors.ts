/**
 * Fixed validation messages from AdultSitePolicy.kt and AdultSiteRuleStore.kt.
 * Longer messages come first because one message can contain another.
 */
const DOMAIN_RULE_MESSAGES = [
  'Website addresses with credentials are not supported',
  'Local and IP addresses are not supported',
  'Enter a site, not a public domain suffix',
  'Enter a complete domain like example.com',
  'You can block up to 100 additional sites',
  'Only website addresses can be blocked',
  'Enter a valid website address',
  'Enter a domain like example.com',
  'IP addresses are not supported',
  'That site is too long',
];

/**
 * The native bridge wraps a rejected domain in the method name and Java exception class.
 * Show only a known validation message, never the raw native text.
 */
export function getAddDomainErrorMessage(cause: unknown): string {
  const text = cause instanceof Error ? cause.message : '';
  const known = DOMAIN_RULE_MESSAGES.find((message) => text.includes(message));
  return known ? `${known}.` : 'That site could not be added.';
}
