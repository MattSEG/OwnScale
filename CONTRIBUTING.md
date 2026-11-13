# Contributing

Issues and pull requests are welcome. Useful areas include firmware compatibility, accessibility, clearer onboarding, and independently validated model evaluation.

1. Read the [architecture](docs/ARCHITECTURE.md) and [model assumptions](docs/MODELS.md).
2. Run `./tests/run.sh`; run the synthetic device suite when changing Android storage/UI behavior.
3. Add synthetic regression cases for calculations, import/export, and schema changes.
4. Keep the Java and Python reconstruction/export behavior aligned.
5. Preserve original observations and packet identities. Tuning must remain reversible.
6. Do not commit personal data, database exports, captures, health screenshots, identifiers, credentials, signing material or toolchain binaries. Check `git diff --cached` before pushing.

Never imply that passing software tests validates body-fat accuracy. New body-composition methods should document their source, intended population, assumptions and evaluation evidence.

When reporting a scale issue, include the model/firmware if known, Android version, build revision, steps and redacted error text. Prefer synthetic minimal packet examples. Do not upload your entire backup or logcat.

Contributions are offered under this project's MIT license. Do not copy third-party code or assets unless their license is compatible and their required notices are retained. Blue2Scale has a separate source-available license; see [NOTICE](NOTICE.md).
