import * as Linking from 'expo-linking';

export const PRIVACY_POLICY_URL =
  'https://github.com/MaximilianMauroner/zen-mode/blob/privacy-policy/PRIVACY.md';

export async function openPrivacyPolicy(): Promise<void> {
  await Linking.openURL(PRIVACY_POLICY_URL);
}
