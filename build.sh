#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source tools/env.sh
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_JAR:=${ANDROID_HOME}/platforms/android-35/android.jar}"
: "${BUILD_TOOLS:=${ANDROID_HOME}/build-tools/35.0.0}"
export PATH="$BUILD_TOOLS:$PATH"
for tool in python3 java javac jar keytool aapt2 d8 zip zipalign apksigner; do
  command -v "$tool" >/dev/null || { echo "Missing $tool; see docs/BUILDING.md" >&2; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo 'Install platforms;android-35 or set ANDROID_JAR.' >&2; exit 1; }
mkdir -p build private
rm -f build/ownscale.apk build/ownscale.apk.idsig
chmod 700 private
rm -rf build/gen build/classes build/dex
mkdir -p build/gen build/classes build/dex
# Debuggable only for explicit local development / adb database extraction.
python3 - "${DEBUGGABLE:-false}" <<'PY'
import sys
s=open('android/AndroidManifest.xml').read()
s=s.replace('android:debuggable="true"', 'android:debuggable="'+('true' if sys.argv[1]=='true' else 'false')+'"')
open('build/AndroidManifest.xml','w').write(s)
PY
aapt2 compile --dir android/res -o build/resources.zip
aapt2 link -o build/base.apk -I "$ANDROID_JAR" --manifest build/AndroidManifest.xml --java build/gen build/resources.zip
find android/src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 --release 8 -classpath "$ANDROID_JAR" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
d8 --lib "$ANDROID_JAR" --min-api 34 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
# Local builds get their own key. Official releases explicitly select a persistent key.
if [ -n "${SIGNING_KEYSTORE:-}" ]; then
  : "${SIGNING_PASSWORD_FILE:?Set SIGNING_PASSWORD_FILE for the release key}"
  [ -f "$SIGNING_KEYSTORE" ] && [ -f "$SIGNING_PASSWORD_FILE" ] || { echo 'Signing key or password file missing.' >&2; exit 1; }
  apksigner sign --ks "$SIGNING_KEYSTORE" --ks-key-alias "${SIGNING_KEY_ALIAS:-ownscale}" --ks-pass "file:$SIGNING_PASSWORD_FILE" --out build/ownscale.apk build/aligned.apk
else
  if [ ! -f private/signing.keystore ]; then
    keytool -genkeypair -keystore private/signing.keystore -storepass android -keypass android -alias ownscale -dname 'CN=OwnScale Local Build' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
    chmod 600 private/signing.keystore
  fi
  apksigner sign --ks private/signing.keystore --ks-pass pass:android --out build/ownscale.apk build/aligned.apk
fi
apksigner verify build/ownscale.apk
printf 'Built build/ownscale.apk\n'
