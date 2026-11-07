# Changelog

## Unreleased

### New

- Monitoring sessions are now saved, with start, end, monitored time and summary values. Nights recorded before this update are grouped by date but have no score, because their monitored time was never recorded (#14).
- New Stats tab with a nightly snore score from 0 to 100 (lower is better), an explanation of how it is calculated, a chart over a week, month, 3 months or a year with a 7-day average, markers for app updates and setting changes, and a comparison of the last 30 days with the 30 days before (#14).
- Nightly summaries can be exported as CSV and are included in the full ZIP export as `nightly_summaries.csv` (#14).

### Fixes

- The snore in progress when monitoring stops is now saved instead of being dropped.

## 1.1.1

### Fixes

- Restored 1.0.0 audio capture for built-in, USB, and wired microphones (raw `MIC` source, no speech noise suppressor). Fixes dB readings ~20 dB too high and snoring no longer being detected (#9, #12). Bluetooth microphones still use `VOICE_RECOGNITION`.
- Upgrades now keep your snoring history: replaced the destructive database migration with a real migration from 1.0.0 (#6).
- Fixed Settings method cards stretching to a full screen with "Always Active" written one letter per line on narrow screens or larger fonts (#10).
- Rotating the screen no longer jumps back to the dashboard or loses the history list position (#11).

### Note

- dB readings return to the 1.0.0 scale. If you raised the Sound Volume threshold in 1.1.0 to compensate for the higher readings, lower it again (default: 55 dB).

## 1.1.0

### Improved audio detection and reliability

- Added support for built-in, Bluetooth, USB, and wired microphones.
- Fixed USB audio leakage that could cause media playback to be detected as snoring.
- Improved media playback handling and automatic detection resume.
- Fixed incorrect "Fallback active" status for the phone microphone.
- Added real-time snoring event notifications.
- Added automatic error logging and improved error handling.

### Improved user experience

- Reorganized the Settings screen.
- Added Material You theming and dark mode.
- Improved scrolling performance.
- Expanded data export to include snoring audio.
- Added and improved the in-app guide and documentation.
- Updated screenshots and README.
