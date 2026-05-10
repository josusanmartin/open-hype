#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-emulator-5560}"
ADB="${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"

echo "Waiting for $SERIAL..."
"$ADB" -s "$SERIAL" wait-for-device >/dev/null

for _ in $(seq 1 90); do
  if [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  sleep 2
done

echo "Reconnecting AndroidWifi on $SERIAL..."
"$ADB" -s "$SERIAL" shell cmd wifi connect-network AndroidWifi open >/dev/null 2>&1 || true
sleep 4

echo "Connectivity:"
"$ADB" -s "$SERIAL" shell dumpsys connectivity | rg -n "Active default network|network\\{|DnsAddresses|Routes:" || true
