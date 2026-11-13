#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
ADB="${ADB:-adb}"
TEST="${1:-AppVerification}"
case "$TEST" in AppVerification|HealthVerification) ;; *) echo 'Unknown test' >&2; exit 2;; esac
mkdir -p build
cp android/AndroidManifest.xml build/manifest-test-backup.xml
cleanup() { cp build/manifest-test-backup.xml android/AndroidManifest.xml; rm -f "android/src/org/ownscale/app/$TEST.java"; }
trap cleanup EXIT
cp "tests/$TEST.java" "android/src/org/ownscale/app/$TEST.java"
python3 - "$TEST" <<'PY'
import sys
p='android/AndroidManifest.xml';s=open(p).read();s=s.replace('</manifest>',f'<instrumentation android:name=".{sys.argv[1]}" android:targetPackage="org.ownscale.app"/></manifest>');open(p,'w').write(s)
PY
DEBUGGABLE=true ./build.sh
"$ADB" install --no-incremental -r build/ownscale.apk
"$ADB" shell am instrument -w "org.ownscale.app/.$TEST" | tee "build/$TEST-result.txt"
python3 - "$TEST" <<'PY'
import sys
s=open('build/'+sys.argv[1]+'-result.txt').read();assert 'INSTRUMENTATION_CODE: -1' in s and 'INSTRUMENTATION_RESULT: error=' not in s,s
PY
cleanup
trap - EXIT
./build.sh
"$ADB" install --no-incremental -r build/ownscale.apk
