#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.20+101}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/mise/installs/android-sdk/23.0}"
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE="${GRADLE:-$HOME/.local/share/mise/installs/gradle/8.7.0/gradle-8.7/bin/gradle}"
if (( $# == 0 )); then set -- :app:assembleDebug; fi
"$GRADLE" --console=plain "$@"
