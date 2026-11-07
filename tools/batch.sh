#!/usr/bin/env bash
# Run shell commands from stdin on the TV, one per line, printing each with its output.
# Uses the adb binary when it has a device (needed for Android 11+ "Wireless debugging", which uses
# TLS pairing that adb-shell can't do); otherwise falls back to tools/tv.py over port 5555.
# Pair once: adb pair <ip:pairing-port> <code>; adb connect <ip:port>   (adb mdns services lists both)
set -uo pipefail
cd "$(dirname "$0")"
if command -v adb >/dev/null && [ "$(adb get-state 2>/dev/null)" = device ]; then
  while IFS= read -r line; do
    case "$line" in ''|\#*) continue ;; esac
    echo "\$ $line"
    adb shell "$line" </dev/null
  done
else
  exec venv/bin/python -I tv.py --batch
fi
