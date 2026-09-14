import Constants from 'expo-constants';
import * as Linking from 'expo-linking';
import { Platform } from 'react-native';

import { buildFeedbackIssueUrl } from './github-issue-url';

export async function openFeedbackIssue(): Promise<void> {
  const version = Constants.expoConfig?.version ?? 'Unknown';
  const platform = Platform.OS.charAt(0).toUpperCase() + Platform.OS.slice(1);

  await Linking.openURL(buildFeedbackIssueUrl({ version, platform }));
}
