#!/usr/bin/env bash
# Source after changing to the repository root.
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]; then
  SDK_ROOT="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
  export PATH="$SDK_ROOT/platform-tools:$PATH"
fi
