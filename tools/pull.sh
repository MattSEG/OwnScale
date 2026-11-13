#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
ADB="${ADB:-adb}"
mkdir -p private
chmod 700 private
"$ADB" shell am force-stop org.ownscale.app
TEMP_DB=$(mktemp private/ownscale.XXXXXX)
trap 'rm -f "$TEMP_DB"' EXIT
"$ADB" exec-out run-as org.ownscale.app cat databases/ownscale.db > "$TEMP_DB"
python3 - "$TEMP_DB" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1]);assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
PY
if [ -f private/ownscale.db ]; then cp private/ownscale.db "private/ownscale-$(date +%Y%m%d-%H%M%S).db"; fi
mv "$TEMP_DB" private/ownscale.db
python3 tools/export.py private/ownscale.db --out private/export
if [ "${REOPEN_APP:-1}" = "1" ]; then "$ADB" shell am start -n org.ownscale.app/.MainActivity; fi
