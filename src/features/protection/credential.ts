import { pbkdf2Async } from '@noble/hashes/pbkdf2';
import { sha256 } from '@noble/hashes/sha256';
import { bytesToHex, hexToBytes, utf8ToBytes } from '@noble/hashes/utils';
import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

const CREDENTIAL_KEY = 'zen-mode.protection-credential.v1';
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
  return (await SecureStore.getItemAsync(CREDENTIAL_KEY)) !== null;
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
    const value = JSON.parse(serialized) as Partial<CredentialRecord>;
    if (
      value.version !== 1 ||
      value.algorithm !== 'pbkdf2-sha256' ||
      value.iterations !== ITERATIONS ||
      typeof value.salt !== 'string' ||
      typeof value.verifier !== 'string' ||
      value.salt.length !== 32 ||
      value.verifier.length !== DERIVED_KEY_LENGTH * 2
    ) {
      return null;
    }
    return value as CredentialRecord;
  } catch {
    return null;
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
