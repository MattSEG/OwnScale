# Data, exports and backups

SQLite database: app-private `databases/ownscale.db`. Schema version 2 adds compatible columns when opening older backups. Never commit a real database or export.

| Table | Purpose |
|---|---|
| observations | Immutable source metric values, timestamps, record IDs, methods and JSON metadata |
| capture_sessions | Device/profile context and capture status |
| raw_packets | Captured notifications, checksums and stable packet identity |
| model_baselines | Original model baseline and assumption provenance |
| calibrations | Versioned anchor, reference, sensitivity and historical fat fraction |
| annotations | Notes and reversible exclusions |
| app_config | Settings and selected tuning version |
| health_sync | Stable client IDs, fingerprints and external sync outcomes |

Times are stored as UTC and displayed in local time. Tuning recalculates a view; it does not overwrite imported observations or captured packets. CSV preserves numeric precision.

## CSV

One grouped row per reading. Key fields:

- `body_fat_percent`: current stabilized result (or individual result when stabilization is off).
- `body_fat_individual_percent`: current individual adjusted/reconstructed result.
- `body_fat_original_percent`: untouched imported or initially captured estimate.
- `body_fat_method`: identifies original, experimental or historical-reconstruction output.
- `lookback_fat_fraction`: historical fat-allocation assumption, e.g. `0.75`.
- Source, measured time, kg, waist cm, note, exclusion state, reading ID and calibration ID.

Notes beginning with spreadsheet formula prefixes are neutralized in grouped CSV. The desktop `observations.csv` is a raw metric-level export and should be handled as untrusted text when imported into other software.

## JSON and SQLite

JSON includes original observations, packets, sessions, baselines, calibration history, notes and settings. Restore merges missing observations/packets by identity, restores settings/tuning, and leaves original values intact. It disables auto-connect and sync for review. Failed restores roll back. External Health Connect receipts are not restored from JSON.

SQLite export contains the full database, including Health Connect sync state. These files may contain profile attributes, device addresses, exact timestamps, notes and raw health data. They are not encrypted by the app after export.

## Health Connect

OwnScale uses stable client record IDs and versioned updates for its own records. Imported Hume records are not written back. Turning sync off stops future writes; it does not remove records already written. Excluding a local reading also leaves previously written external records intact. Health Connect's BF record cannot carry the app's full model explanation, so another app may display an experimental estimate as an ordinary BF percentage.
