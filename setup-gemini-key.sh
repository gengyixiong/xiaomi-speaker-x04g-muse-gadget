#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
if [[ "${1:-}" != --prompt ]]; then
    if command -v foot >/dev/null && [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
        exec foot --title='Gemini API Key' --hold bash "$project_dir/setup-gemini-key.sh" --prompt
    fi
fi
umask 077
read -r -s -p 'Enter Gemini API Key: ' gemini_key
printf '\n'
gemini_key="${gemini_key//$'\r'/}"
if [[ -z "$gemini_key" || ${#gemini_key} -gt 4096 ]]; then
    printf 'Enter a non-empty API key on one line.\n' >&2
    exit 1
fi
mkdir -p "$project_dir/.secrets"
chmod 700 "$project_dir/.secrets"
printf '%s' "$gemini_key" > "$project_dir/.secrets/gemini-api-key"
chmod 600 "$project_dir/.secrets/gemini-api-key"
unset gemini_key
printf 'Saved locally. Rebuild and reinstall the APK to apply.\n'
