#!/usr/bin/env bash
set -euo pipefail
bash scripts/prepare-nightly-space.sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0'
curl --fail --silent --show-error --location \
  https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar \
  --output "$ANDROID_BUNDLETOOL_JAR"
echo 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29  '"$ANDROID_BUNDLETOOL_JAR" | sha256sum --check --status
if [ "${1:-}" = eas ]; then npm install --global eas-cli@20.5.1; fi
