#!/usr/bin/env bash
set -euo pipefail
bash scripts/prepare-nightly-space.sh
# The hosted image installs command-line tools without adding them to PATH.
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
if [ -n "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ] && [ "$ANDROID_HOME" != "$ANDROID_SDK_ROOT" ]; then
  echo 'ANDROID_HOME and ANDROID_SDK_ROOT must match' >&2
  exit 1
fi
export ANDROID_HOME="$sdk_root" ANDROID_SDK_ROOT="$sdk_root"
sdk_bin="$sdk_root/cmdline-tools/latest/bin"
test -x "$sdk_bin/sdkmanager"
export PATH="$sdk_bin:$sdk_root/platform-tools:$PATH"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$sdk_root" "$sdk_root" >> "$GITHUB_ENV"
printf '%s\n' "$sdk_bin" "$sdk_root/platform-tools" >> "$GITHUB_PATH"
"$sdk_bin/sdkmanager" --sdk_root="$sdk_root" 'platforms;android-36' 'build-tools;36.0.0'
curl --fail --silent --show-error --location \
  https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar \
  --output "$ANDROID_BUNDLETOOL_JAR"
echo 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29  '"$ANDROID_BUNDLETOOL_JAR" | sha256sum --check --status
if [ "${1:-}" = eas ]; then npm install --global eas-cli@20.5.1; fi
