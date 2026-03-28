# Health Activity Widget

An Android home-screen widget that displays your Health Connect activity as a GitHub-style contribution calendar grid.

Each column is a week, each row a day of the week. Cells light up when you hit your activity goals:

- **Step goal** — 10,000 steps/day
- **Exercise sessions** — any recorded exercise type

Days with multiple activity types show colour bands. The widget refreshes hourly in the background and caches the last result so it never shows a blank grid on wake.

## Features

- 13-week contribution grid (resizable)
- Per-activity-type colour coding with colour picker
- Toggle individual activity types on/off
- Reads data from Android Health Connect — no data leaves your device
- No ads, no tracking, no internet permission

## Requirements

- Android 8.0 (API 26) or higher
- [Health Connect](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata) installed and containing activity data

> **Note for de-Googled ROMs:** Health Connect is a Google app. If it is not installed, the widget will display a prompt to grant permissions that cannot be fulfilled.

## Building

```bash
./gradlew assembleRelease
```

The APK will be at `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Privacy

All data is read locally from Health Connect and never transmitted anywhere. See [privacy_policy.html](privacy_policy.html) for the full policy.

## License

MIT — see [LICENSE](LICENSE).
