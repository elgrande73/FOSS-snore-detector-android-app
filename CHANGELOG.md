# Changelog

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
