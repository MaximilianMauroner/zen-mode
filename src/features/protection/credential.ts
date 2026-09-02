import { pbkdf2Async } from '@noble/hashes/pbkdf2';
import { sha256 } from '@noble/hashes/sha256';
import { bytesToHex, hexToBytes, utf8ToBytes } from '@noble/hashes/utils';
import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

const CREDENTIAL_KEY = 'zen-mode.protection-credential.v1';
const PASSWORD_PROTECTION_KEY = 'zen-mode.password-protection-enabled.v1';
const SETUP_COMPLETE_KEY = 'zen-mode.setup-complete.v1';
const ITERATIONS = 210_000;
const DERIVED_KEY_LENGTH = 32;
const MINIMUM_PASSWORD_LENGTH = 8;

type CredentialRecord = {
  version: 1;
  algorithm: 'pbkdf2-sha256';
  iterations: number;
  salt: string;
  verifier: string;
};

export function validatePassword(password: string): string | null {
  if (password.length < MINIMUM_PASSWORD_LENGTH) {
    return `Use at least ${MINIMUM_PASSWORD_LENGTH} characters.`;
  }
  return null;
}

export async function hasProtectionPassword(): Promise<boolean> {
  const serialized = await SecureStore.getItemAsync(CREDENTIAL_KEY);
  return serialized !== null && parseCredential(serialized) !== null;
}

export async function isPasswordProtectionEnabled(): Promise<boolean> {
  return (await SecureStore.getItemAsync(PASSWORD_PROTECTION_KEY)) === 'true' && (await hasProtectionPassword());
}

export async function enablePasswordProtection(password: string): Promise<void> {
  const validationError = validatePassword(password);
  if (validationError) throw new Error(validationError);

  // SecureStore has no transaction primitive. Keep the old values so a failed
  // second write can restore the state that was visible before this operation.
  const previousMarker = await SecureStore.getItemAsync(PASSWORD_PROTECTION_KEY);
  const previousCredential = await SecureStore.getItemAsync(CREDENTIAL_KEY);

  try {
    await createProtectionPassword(password);
    await SecureStore.setItemAsync(PASSWORD_PROTECTION_KEY, 'true');
  } catch (cause) {
    await restoreStoredValue(CREDENTIAL_KEY, previousCredential);
    await restoreStoredValue(PASSWORD_PROTECTION_KEY, previousMarker);
    throw cause;
  }
}

export async function disablePasswordProtection(): Promise<void> {
  const previousMarker = await SecureStore.getItemAsync(PASSWORD_PROTECTION_KEY);
  await SecureStore.deleteItemAsync(PASSWORD_PROTECTION_KEY);

  try {
    await SecureStore.deleteItemAsync(CREDENTIAL_KEY);
  } catch (cause) {
    // If credential removal fails, restore the marker so a partially disabled
    // protection setup remains enabled and can still be unlocked.
    await restoreStoredValue(PASSWORD_PROTECTION_KEY, previousMarker);
    throw cause;
  }
}

export async function hasCompletedSetup(): Promise<boolean> {
  return (await SecureStore.getItemAsync(SETUP_COMPLETE_KEY)) === 'true';
}

export async function markSetupComplete(): Promise<void> {
  await SecureStore.setItemAsync(SETUP_COMPLETE_KEY, 'true');
}

export async function createProtectionPassword(password: string): Promise<void> {
  const validationError = validatePassword(password);
  if (validationError) throw new Error(validationError);

  const salt = await Crypto.getRandomBytesAsync(16);
  const verifier = await deriveVerifier(password, salt, ITERATIONS);
  const record: CredentialRecord = {
    version: 1,
    algorithm: 'pbkdf2-sha256',
    iterations: ITERATIONS,
    salt: bytesToHex(salt),
    verifier: bytesToHex(verifier),
  };

  await SecureStore.setItemAsync(CREDENTIAL_KEY, JSON.stringify(record));
}

export async function verifyProtectionPassword(password: string): Promise<boolean> {
  const serialized = await SecureStore.getItemAsync(CREDENTIAL_KEY);
  if (!serialized) return false;

  const record = parseCredential(serialized);
  if (!record) return false;

  const actual = await deriveVerifier(password, hexToBytes(record.salt), record.iterations);
  return constantTimeEqual(actual, hexToBytes(record.verifier));
}

async function deriveVerifier(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  return pbkdf2Async(sha256, utf8ToBytes(password), salt, {
    c: iterations,
    dkLen: DERIVED_KEY_LENGTH,
    asyncTick: 10,
  });
}

function parseCredential(serialized: string): CredentialRecord | null {
  try {
    const parsed: unknown = JSON.parse(serialized);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;

    const value = parsed as Partial<CredentialRecord>;
    if (
      value.version !== 1 ||
      value.algorithm !== 'pbkdf2-sha256' ||
      value.iterations !== ITERATIONS ||
      typeof value.salt !== 'string' ||
      typeof value.verifier !== 'string' ||
      !isHex(value.salt, 16) ||
      !isHex(value.verifier, DERIVED_KEY_LENGTH)
    ) {
      return null;
    }
    return value as CredentialRecord;
  } catch {
    return null;
  }
}

function isHex(value: string, byteLength: number): boolean {
  return value.length === byteLength * 2 && /^[0-9a-f]+$/i.test(value);
}

async function restoreStoredValue(key: string, value: string | null): Promise<void> {
  try {
    if (value === null) {
      await SecureStore.deleteItemAsync(key);
    } else {
      await SecureStore.setItemAsync(key, value);
    }
  } catch {
    // Preserve the original operation error. The caller cannot make a
    // best-effort rollback atomic when SecureStore itself is failing.
  }
}

function constantTimeEqual(left: Uint8Array, right: Uint8Array): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left[index] ^ right[index];
  }
  return difference === 0;
}
