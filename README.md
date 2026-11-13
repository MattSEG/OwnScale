# OwnScale

**Your scale. Your history. Your data.**

OwnScale is an experimental, local-first Android app for compatible Hume Body Pod scales. It reads weight and impedance over Bluetooth, keeps the original observations, and lets you inspect, model, graph and export your own history—without an OwnScale account or cloud backend.

A big thank-you to **[Jozef Jarosciak](https://github.com/JozefJarosciak)** and **[Blue2Scale](https://github.com/JozefJarosciak/Blue2Scale.com)**. Their [BLE protocol write-up](https://github.com/JozefJarosciak/Blue2Scale.com/blob/master/BLE_SCALE_PROTOCOL.md) made this implementation possible. See [attribution and licensing](NOTICE.md).

> Early experimental software. Body-fat outputs are estimates and user-selected scenarios, not validated measurements. OwnScale is independent of Hume and Blue2Scale and is not endorsed by either.

## Download and install

### [Download OwnScale for Android (.apk)](https://github.com/MattSEG/OwnScale/releases/latest/download/OwnScale.apk)

**Android 14 or newer. No build tools or account required.**

1. Open the download link on your Android phone.
2. Open `OwnScale.apk`. If Android asks, allow **Install unknown apps** for the browser or file manager you used.
3. Tap **Install**, open OwnScale, and enter your profile in Settings. Allow Nearby devices when prompted; Health Connect access is optional.

You can turn off that browser/file manager's install permission afterward. Future official APKs use the same signing identity so they can update in place. Export a backup before uninstalling or switching from a self-built version with a different signature.

[Release notes and checksums](https://github.com/MattSEG/OwnScale/releases/latest) · [Build from source](#build-and-install)

## What it does

- **Direct Bluetooth capture:** stable weight, impedance observations, and original notification packets. Connect automatically while the app is open, or start manually.
- **A focused daily dashboard:** weight, body-fat estimate, weekly average, and measurement status.
- **Trends:** weight, body fat and optional manually entered waist measurements; selectable ranges, original-data comparison, and a separate toggleable stabilization line.
- **Personal tuning:** choose a reference scan, anchor percentage, sensitivity, and response time. Revisit old tuning versions without rewriting original observations.
- **Weight-informed lookback:** reconstruct imported Hume history from weight changes, with an adjustable fat/lean allocation (default 75% / 25%).
- **Health Connect:** import available Hume history and optionally write OwnScale weight/body-fat estimates using stable record IDs. Imported Hume records are never uploaded again.
- **Data ownership:** CSV sharing, JSON backup/restore, SQLite export, and a portable Python exporter. No runtime Internet permission or analytics SDK.
- **Material 3 Expressive-inspired UI:** light/dark palettes, emphasized measurements, connected controls, and labeled navigation. Implemented with native Android Views rather than the Material Components library.

## Requirements and scope

- Android **14 or newer** (API 34+), Bluetooth LE, and a compatible Body Pod.
- The implementation targets the **Body Pod 2 / Lefu protocol** documented by Blue2Scale. Compatibility across other models and firmware versions is unverified.
- Health Connect permissions are needed only for import/sync. Existing Hume history must already be available through Health Connect; this app does not log into or download from Hume's cloud.
- Follow your scale manufacturer's contraindications and instructions for impedance measurements.

## Build and install

Install **JDK 17**, Python 3, Bash, `zip`, and the Android SDK command-line tools. Then:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
export ANDROID_HOME="/path/to/Android/Sdk"
export JAVA_HOME="/path/to/jdk-17"
./tests/run.sh
./build.sh
"$ANDROID_HOME/platform-tools/adb" install -r build/ownscale.apk
```

The build generates a private signing key for your checkout. **Keep that key to install updates without uninstalling. Never commit it.** The normal build is not debuggable. Official prebuilt APKs are available from [GitHub Releases](https://github.com/MattSEG/OwnScale/releases/latest). Your local build key differs from the official release key.

Full instructions, overrides, Android permissions and troubleshooting: **[Building and setup](docs/BUILDING.md)**.

## First use

1. In **Settings**, enter your height, age and profile, then save. No personal profile or body-fat anchor ships with the app.
2. Wake your scale, close other apps connected to it, open OwnScale and measure with the handle. Raw weight and impedance are retained.
3. Review **body-fat tuning** before relying on the estimate. The initial baseline uses the uncalibrated model output; no particular person's assumed percentage is preloaded.
4. To import history, allow Health Connect weight/body-fat reads and past-data access, then import Hume history.
5. In **Trends → Body fat**, turn on original-estimate comparison, review the reconstructed history, and adjust the fat/lean allocation if appropriate.
6. Enable Health Connect write switches only if you want to share OwnScale estimates with other apps. Export a backup regularly.

For repeatability, use a consistent measurement routine. The app stops its connection when backgrounded; it is not a background scale logger.

## Understand the estimates

There are three distinct things in the app:

| Series | Meaning |
|---|---|
| Original observations | What was imported or captured, including Hume's reported BF% and the original experimental scan estimate |
| Individual adjusted estimate | Current tuning applied to a single OwnScale model result, or a weight-based historical reconstruction |
| Stabilized estimate | A time-aware view that reduces the influence of closely spaced readings; shown on Today and used for optional OwnScale BF sync |

A historical reconstruction assumes how weight change was divided between fat and lean mass. It cannot recover the actual composition of past weight changes. A personal anchor also does not validate the impedance equation.

Read **[How the models work](docs/MODELS.md)** for equations, a synthetic example, smoothing behavior and limitations.

## Documentation

- [Building, installation and troubleshooting](docs/BUILDING.md)
- [Models, tuning and historical reconstruction](docs/MODELS.md)
- [Architecture and data flow](docs/ARCHITECTURE.md)
- [Schema, exports and backups](docs/DATA.md)
- [Privacy and permissions](docs/PRIVACY.md)
- [Contributing and test instructions](CONTRIBUTING.md)
- [Security reporting](SECURITY.md)
- [Attribution](NOTICE.md) · [License](LICENSE)

## Validation and limitations

The test suite covers protocol checksums, stable/live weight distinction, model arithmetic, elapsed-time stabilization, historical reconstruction, and synthetic desktop exports. Optional Android instrumentation exercises an isolated synthetic database, tuning undo, backup/restore and UI rendering. Tests establish software behavior, **not body-fat accuracy**.

Bluetooth behavior, Health Connect availability and permissions can differ by device. The app is for a single local profile; it does not automatically identify household members. There is no automatic cloud backup. JSON/CSV/SQLite exports contain sensitive information, and Android's share sheet hands attachments to the app you choose.

## Credit and license

OwnScale's original code is offered under the [MIT License](LICENSE). Blue2Scale is separately **source-available**, with its own [license](https://github.com/JozefJarosciak/Blue2Scale.com/blob/master/LICENSE); this project's MIT license does not relicense Blue2Scale. No Blue2Scale application source, documentation files, logos, or assets are vendored here. Protocol facts and the model description informed this original implementation; it is not represented as clean-room work.

Design references: [Material Design 3](https://m3.material.io/) and [Google's Material 3 Expressive research](https://design.google/library/expressive-material-design-google-research).
