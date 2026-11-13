# Privacy and permissions

OwnScale has no runtime Internet permission, advertising SDK, analytics SDK or app account. Health data is stored in its Android app-private SQLite database. Android cloud backup is disabled.

Permissions are used for:

- **Bluetooth scan/connect:** discover and communicate with the scale while the app is open.
- **Health Connect reads/history:** import records the user permits and that are actually present.
- **Health Connect writes:** optionally publish OwnScale weight and experimental BF estimates.
- **Export/share:** Android's document picker and temporary read-only attachment grants hand selected files to destinations chosen by the user.

No database, raw capture, real profile, device address, private screenshot, signing key, or account credential is included in the repository. Test data is synthetic or drawn from publicly documented protocol examples. A signing key is generated only in your local ignored `private/` directory.

Exported data is sensitive. The app does not encrypt exported files or control the email/cloud app you share them with. Bluetooth capture metadata and backups can contain device identifiers and profile fields. Review attachments before sharing a bug report.

Health Connect data remains subject to Android permissions and the other apps you authorize. Disabling sync or uninstalling OwnScale does not necessarily remove previously written records from Health Connect. Manage those records through Android's Health Connect controls.
