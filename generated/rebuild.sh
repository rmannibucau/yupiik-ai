#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$ROOT"

# The normal reactor install also renders and validates the aggregated site.
mvn spotless:apply clean install -T1C
for module in coding-agent/yca coding-agent/extensions/notifications-service; do
  (
    cd -- "$ROOT/$module"
    mvn arthur:native-image -Pnative
  )
done

# Keep installed binaries outside target, and replace them atomically so a
# running YCA or notification service never has its executable overwritten.
BIN="$ROOT/generated/bin"
mkdir -p -- "$BIN"
STAGING=$(mktemp -d "$BIN/.rebuild.XXXXXX")
trap 'rm -rf -- "$STAGING"' EXIT
install -m 755 "$ROOT/coding-agent/yca/target/yca-bin.graal.bin" "$STAGING/yca"
install -m 755 "$ROOT/coding-agent/extensions/notifications-service/target/notifications-service.graal.bin" \
  "$STAGING/notifications-service"
mv -f -- "$STAGING/yca" "$BIN/yca"
mv -f -- "$STAGING/notifications-service" "$BIN/notifications-service"

# Restart the tray after staging, or start it if it is currently stopped.
# Restarting forgets unread reminders, which are intentionally in memory only.
systemctl --user restart yca-notifications.service

scp -q "$BIN/yca" \
  VS144@LIN0176875:/home/VS144/Installations/yca
