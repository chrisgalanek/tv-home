#!/usr/bin/env bash
# Put the bedroom Sony BRAVIA back exactly as it was before the debloat:
#   - re-enables the 11 packages in debloat.txt
#   - re-enables Google TV's stock launcher (with Gemini) and its setup app, and makes the stock
#     launcher the home screen again
#   - with --uninstall, also removes the new home screen (com.mihir.tvhome)
# Packages that were already disabled before we started (Netflix, Sony promos, ...) stay disabled.
#
# Needs an ADB connection. Wireless debugging changes port when the TV restarts, so if this says
# "no devices": on the TV open Developer options → Wireless debugging → Pair device with pairing code, then
#   adb mdns services                       # shows the TV's pairing and connect ip:port
#   adb pair <pairing ip:port> <code>
#   adb connect <connect ip:port>
#
# Usage: examples/sony-bravia-ae2/restore.sh [--uninstall]
set -euo pipefail
cd "$(dirname "$0")/../.."

devices=$(adb devices | awk 'NR>1 && $2=="device"{print $1}')
if [ -z "${ANDROID_SERIAL:-}" ]; then
  [ -n "$devices" ] || { echo "No TV connected over ADB. See the comment at the top of this script."; exit 1; }
  export ANDROID_SERIAL=$(echo "$devices" | head -1)
fi
echo "Restoring $(adb shell getprop ro.product.model | tr -d '\r') at $ANDROID_SERIAL"

STOCK_LAUNCHER=com.google.android.apps.tv.launcherx/.home.HomeActivity \
  tools/restore.sh examples/sony-bravia-ae2/debloat.txt

if [ "${1:-}" = "--uninstall" ]; then
  adb shell pm uninstall com.mihir.tvhome
fi
echo "Home screen now: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.HOME | tail -1 | tr -d '\r')"
