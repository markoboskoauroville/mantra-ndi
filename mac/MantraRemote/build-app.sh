#!/bin/sh
# Builds "Mantra Remote.app" next to this script: swift build, then the bundle (Info.plist with the local network
# and Bonjour keys macOS asks for before it lets an app browse _mantralink._tcp), signed ad hoc.
#   cd mac/MantraRemote && ./build-app.sh && open "Mantra Remote.app"
set -e
cd "$(dirname "$0")"
swift build -c release
BIN="$(swift build -c release --show-bin-path)/MantraRemote"
APP="Mantra Remote.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN" "$APP/Contents/MacOS/MantraRemote"
cp Info.plist "$APP/Contents/Info.plist"
codesign --force --deep -s - "$APP" 2>/dev/null || true
echo "built $APP"
