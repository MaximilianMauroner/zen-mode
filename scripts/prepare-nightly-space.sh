#!/usr/bin/env bash
set -euo pipefail
# Remove only unused preinstalled toolchains before checks, reserve, build, or upload.
if [ "$(df --output=avail -B1 "$GITHUB_WORKSPACE" | tail -1)" -lt 32212254720 ]; then
  sudo rm -rf /usr/share/dotnet /opt/ghc /usr/local/.ghcup /opt/hostedtoolcache/CodeQL
fi
test "$(df --output=avail -B1 "$GITHUB_WORKSPACE" | tail -1)" -ge 16106127360
test "$(awk '/MemTotal/ {print $2}' /proc/meminfo)" -ge 8388608
