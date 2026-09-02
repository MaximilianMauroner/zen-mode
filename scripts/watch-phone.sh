#!/usr/bin/env bash

set -euo pipefail

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not installed or is not on PATH." >&2
  exit 1
fi

device_serials="$(adb devices | awk '$2 == "device" { print $1 }')"
device_count="$(printf '%s\n' "$device_serials" | awk 'NF { count += 1 } END { print count + 0 }')"

if [ "$device_count" -eq 0 ]; then
  echo "No authorized Android phone is connected." >&2
  echo "Connect the phone, unlock it, and accept the USB debugging prompt." >&2
  exit 1
fi

if [ "$device_count" -gt 1 ]; then
  echo "More than one Android device is connected. Disconnect the extras and try again:" >&2
  printf '%s\n' "$device_serials" >&2
  exit 1
fi

device_serial="$device_serials"
echo "Mirroring Android device ${device_serial}. Close the mirror or disconnect the phone to stop."

if command -v scrcpy >/dev/null 2>&1; then
  exec scrcpy --serial "$device_serial" --no-audio --window-title "Zen Mode phone"
fi

if ! command -v ffplay >/dev/null 2>&1; then
  echo "scrcpy is not installed, and ffplay is not available for the fallback mirror." >&2
  echo "Install scrcpy with Homebrew: brew install scrcpy" >&2
  exit 1
fi

echo "scrcpy is not installed. Using Android screenrecord with ffplay."
echo "The fallback ends after Android's screenrecord time limit. Install scrcpy for long sessions."

adb -s "$device_serial" exec-out screenrecord --output-format=h264 --bit-rate 8m - |
  ffplay -loglevel warning -window_title "Zen Mode phone" -f h264 -framerate 30 -sync video -i -
