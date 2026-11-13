# Building and setup

## Toolchain

The project is plain Java/native Android Views, built with Android SDK command-line tools. It has no Gradle or runtime third-party dependencies. Build on macOS or Linux; Windows users can use an appropriate Linux environment with working device access.

Install JDK 17, Python 3, Bash, `zip`, and the Android SDK. Accept the Android SDK licenses locally. Install:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Set `JAVA_HOME` and `ANDROID_HOME` to your installations. Run `./tests/run.sh`, then `./build.sh`. The output is `build/ownscale.apk`.

Advanced overrides: `ANDROID_JAR` can point directly to the API 35 `android.jar`; `BUILD_TOOLS` can point to an installed build-tools directory. `ANDROID_SDK_ROOT` is accepted when `ANDROID_HOME` is unset. No local paths belong in committed scripts.

## Installation and signing

Enable USB debugging and approve your computer on the device, then:

```sh
adb install -r build/ownscale.apk
```

Application ID: `org.ownscale.app`. The public app has a separate identity from the original personal prototype.

The first build generates `private/signing.keystore`, a local sideloading key with a conventional development password. It is ignored by Git. Preserve it securely: updates must use the same key. This simple build is not a production signing pipeline; do not publish the key or treat its password as a release-signing secret. The normal APK has `debuggable=false` even though it uses a locally generated key.

If Android reports an incompatible signature, do not uninstall until you have exported a backup. Uninstalling deletes app-local data. A different checkout's key will not update an existing installation signed by another key.

## Profile and BLE

Save height, age, the equation's sex profile and activity level before measuring. Wake the scale and close apps already connected to it. Allow Nearby devices permissions. Stand on the scale and use the handle according to its instructions. Auto-connect only operates while OwnScale is foregrounded; it can be disabled in Settings.

Weight-only or incomplete captures remain available. The experimental impedance calculation needs complete compatible data. Unknown firmware/protocol variations may need investigation; use synthetic or carefully redacted packet examples in reports.

## Health Connect

Read access, historical read access and write access are separate permissions. Grant them through Android's UI. Enable Hume's own Health Connect sync first if you want its records to be available for import. A successful import cannot include records Hume never placed in Health Connect.

Weight and body-fat writes are optional and disabled initially. Android/Health Connect support and policy can affect permission availability. Distribution through Google Play would additionally require the applicable Health Connect declarations/review; this repository is not a Play Store listing.

## Exports and adb

The in-app CSV/JSON/SQLite exports work with the normal build. For direct `adb run-as` database extraction, explicitly build and install a debuggable version:

```sh
DEBUGGABLE=true ./build.sh
adb install -r build/ownscale.apk
REOPEN_APP=0 ./tools/pull.sh
```

The pull script stops the app for a consistent SQLite copy and writes into ignored `private/`. Do not run it during a measurement. Set `ADB` to override the adb executable. For an exported SQLite file:

```sh
python3 tools/export.py /path/to/ownscale.db --out private/export
```

## Testing

`./tests/run.sh` runs pure Java arithmetic/protocol tests and the Python synthetic export test without a phone.

`./tests/run-device.sh` installs temporary instrumentation, tests an isolated in-memory synthetic store, renders all four screens, and reinstalls a normal APK. It does not populate your live database with fixtures. Use a test device/emulator where possible. On an interrupted run, the script restores source files, but you may need to rebuild/reinstall to remove instrumentation from the device.

`./tests/run-device.sh HealthVerification` is explicitly opt-in: it requires permission and actual OwnScale records, writes selected records to Health Connect, reads them back, and verifies duplicate prevention. Do not run it expecting a synthetic Health Connect sandbox.

## GitHub Actions template

[`ci.example.yml`](ci.example.yml) runs the pure Java/Python tests, builds the APK, and checks tracked files for private artifacts on Linux. To enable it, copy it to `.github/workflows/ci.yml` and push using a GitHub credential with workflow permission. The initial publication credential lacked that scope, so no hosted CI run is claimed for this revision. Local build and synthetic Android tests were run before publication.
