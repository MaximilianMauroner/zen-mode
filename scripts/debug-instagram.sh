#!/usr/bin/env bash

set -euo pipefail

output_path="${1:-instagram-debug-$(date +%Y%m%d-%H%M%S).log}"

if ! adb get-state >/dev/null 2>&1; then
  echo "No authorized Android device is connected." >&2
  exit 1
fi

echo "Recording Zen Guard events to ${output_path}"
echo "Use Instagram now. Press Ctrl+C when the reproduction is complete."
echo "The trace contains resource IDs and guard decisions. It excludes screen text and descriptions."

adb logcat -c
adb logcat -v threadtime -s ZenGuardTrace:I '*:S' | tee "${output_path}"
