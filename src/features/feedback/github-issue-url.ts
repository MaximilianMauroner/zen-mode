const NEW_ISSUE_URL = 'https://github.com/MaximilianMauroner/zen-mode/issues/new';

type FeedbackIssueDetails = {
  version: string;
  platform: string;
};

/** Builds an editable GitHub issue draft. It does not submit anything. */
export function buildFeedbackIssueUrl({ version, platform }: FeedbackIssueDetails): string {
  const body = [
    '## What happened or could be better?',
    '',
    '<!-- Tell us what you noticed. -->',
    '',
    '## What would you prefer?',
    '',
    '<!-- Describe the change you would like. -->',
    '',
    '## App details',
    '',
    `- Zen Mode version: ${version}`,
    `- Platform: ${platform}`,
  ].join('\n');
  const query = new URLSearchParams({ title: '[Feedback] ', body });

  return `${NEW_ISSUE_URL}?${query.toString()}`;
}
