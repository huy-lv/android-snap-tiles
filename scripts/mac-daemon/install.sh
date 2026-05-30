#!/bin/bash
set -e

DAEMON_SRC="$(cd "$(dirname "$0")" && pwd)/adb-snap-daemon.py"
DAEMON_DEST="$HOME/Library/Application Support/adb-snap/adb-snap-daemon.py"
PLIST="$HOME/Library/LaunchAgents/vn.ecdp.adb-snap.plist"
LOGS="$HOME/Library/Logs/adb-snap"

mkdir -p "$(dirname "$DAEMON_DEST")" "$LOGS"
cp "$DAEMON_SRC" "$DAEMON_DEST"
chmod +x "$DAEMON_DEST"

ADB_BIN=$(which adb 2>/dev/null || echo "")
if [[ -z "$ADB_BIN" ]]; then
    for p in /usr/local/bin/adb /opt/homebrew/bin/adb "$HOME/Library/Android/sdk/platform-tools/adb"; do
        [[ -x "$p" ]] && ADB_BIN="$p" && break
    done
fi
ADB_DIR=$(dirname "${ADB_BIN:-/usr/local/bin/adb}")

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>             <string>vn.ecdp.adb-snap</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/python3</string>
    <string>${DAEMON_DEST}</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin:${ADB_DIR}</string>
  </dict>
  <key>RunAtLoad</key>         <true/>
  <key>KeepAlive</key>         <true/>
  <key>StandardOutPath</key>   <string>${LOGS}/daemon.log</string>
  <key>StandardErrorPath</key> <string>${LOGS}/daemon.err</string>
</dict>
</plist>
EOF

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"

echo ""
echo "✓ adb-snap daemon installed and started."
echo ""
echo "  Logs:   $LOGS/"
echo "  Start:  launchctl load $PLIST"
echo "  Stop:   launchctl unload $PLIST"
echo "  Status: launchctl list | grep adb-snap"
echo ""
echo "⚠️  If macOS Firewall is on: System Settings > Network > Firewall > Options"
echo "   Add Python and allow incoming connections."
