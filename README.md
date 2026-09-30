# Health Sync

Health Sync is a small Android app for exporting selected Health Connect data to a single JSON file in Google Drive.

For the current personal setup, Zepp/Amazfit is the preferred wearable source when its data is available. The app writes `health_data.json`, which is then used by the Salute pipeline and dashboard.

## Current workflow

1. Zepp/Amazfit syncs health data into Health Connect.
2. Health Sync reads the selected Health Connect data.
3. The app updates the chosen Google Drive file `health_data.json`.
4. The downstream Salute pipeline imports the useful daily data into Supabase.
5. The Salute dashboard uses those consolidated data for trends, coaching, training and nutrition.

## APK status

The working Health Sync 1.1.0 build is currently installed on the phone and functioning correctly.

The repository intentionally does **not** store generated APK files. GitHub Actions currently compiles the Android app only as a CI check and does not publish new APK artifacts. Permanent release signing can be configured later if future updateable public/stable APKs are needed.

## Data interpretation

Daily summary fields are the authoritative values for daily totals. Raw Health Connect records can overlap across origins and should not be summed blindly.

Useful summary fields include:

- steps
- calories_total_kcal
- calories_active_kcal
- heart_rate_sample_avg_bpm
- heart_rate_resting_bpm
- distance_health_connect_km
- exercise_session_minutes
- sleep.duration_hours
- sleep.sleep_date_local
- sleep.start_local
- sleep.end_local
- sleep.stages_minutes
- hrv_rmssd.median_ms
- selected_summary_origin
- summary_data_origins

When Zepp is selected as the summary origin, use those summary values for the user-facing daily totals.

## Privacy

Do not commit personal health data, generated APK files, signing keys, keystores, screenshots containing health information, `health_data.json`, `local.properties`, or Gradle build output.

## Build

See [SETUP.md](SETUP.md).

The CI workflow verifies that the Android app still compiles, without retaining a new APK artifact.

## License

MIT License. See [LICENSE](LICENSE).
