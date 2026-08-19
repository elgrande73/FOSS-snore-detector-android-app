# Snore Detector

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![F-Droid](https://img.shields.io/badge/F--Droid-FOSS-green.svg)](https://f-droid.org/)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=26)

**Snore Detector** is a privacy-first, 100% open-source Android application for real-time acoustic snore monitoring and sleep analysis.

Designed from the ground up for privacy and performance, all digital signal processing (DSP) happens completely on-device without telemetry, tracking, or cloud dependencies.

---

## 🌟 Key Features

* **Real-Time Acoustic Signal Processing**
  Live audio analysis computing Sound Volume (dB from RMS), Zero-Crossing Rate (ZCR / Pitch), Snoring Frequency Band Energy (100 Hz – 1,000 Hz), and Low-Frequency Spectral Ratio (<500 Hz)
  
* **Real-Time Snoring Alerts & Smartwatch Vibration**
  Optional immediate Android notification when a snoring incident is confirmed, enabling vibration on connected smartwatches and fitness bands via Gadgetbridge or notification sync tools.

* **100% On-Device & Private**
  Zero external network calls or tracking analytics. Your audio and sleep data never leave your device.

* **Configurable DSP Detection Criteria**
  Customize thresholds for each of the 4 DSP acoustic filters and minimum continuous event duration (default 1.0s), with single-tap "Restore All Defaults".

* **Local Event Logging**
  Records detected snoring episodes with timestamps, peak sound volume (dB), duration, and acoustic metrics.

* **Audio Clip Capturing**
  Option to record and save short `.wav` clips of snoring episodes for playback directly within the app history.

* **Data Export & Sovereignty**
  Export your detection history and recordings as a complete `.zip` bundle (CSV + WAV files) or CSV format for personal analysis or medical consultation.

* **Modern Material 3 UI**
  Built with Jetpack Compose following the Sleek Interface theme, edge-to-edge layout, and dark/light adaptive design.

---

## 📱 Anti-Features & F-Droid Compliance

Snore Detector adheres strictly to F-Droid Free and Open Source Software guidelines.

* **No Analytics / Telemetry**
  No Google Analytics, Firebase, or tracking SDKs.

* **No Ads**
  Completely free of advertising networks.

* **No Non-Free Dependencies**
  Built strictly with standard open-source Android Jetpack, Kotlin Coroutines, and Room libraries.

* **Offline First**
  Requires only the `RECORD_AUDIO` and `FOREGROUND_SERVICE` permissions to function locally.

---

## 🛠️ Architecture & Technologies

| Component              | Technology                                                                                                                 |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| **Language**           | Kotlin 100%                                                                                                                |
| **UI Framework**       | Jetpack Compose (Material 3)                                                                                               |
| **Local Database**     | Room DB with KSP symbol processing                                                                                         |
| **Asynchronous Flow**  | Kotlin Coroutines & `StateFlow`                                                                                            |
| **Signal Processing**  | On-device Android `AudioRecord` PCM 16-bit buffer processing with FFT spectral decomposition                               |
| **Foreground Service** | Persistent foreground monitoring service ensuring continuous overnight tracking without OS background execution throttling |

---

## 🚀 Building From Source

### Prerequisites

* Android Studio Ladybug or newer
* JDK 17+
* Android SDK 35 (Minimum SDK 26 / Android 8.0)

### Build Commands

Clone the repository and build using Gradle:

```bash
git clone https://github.com/elgrande73/FOSS-snore-detector-android-app
cd FOSS-snore-detector-android-app
./gradlew assembleRelease
```

---

## 📦 All Releases

* **[View All Releases](https://github.com/elgrande73/FOSS-snore-detector-android-app/releases)** on GitHub

---

## 📄 Contact

```text
Copyright 2026 elgrande73
For questions, contact: elgrande.github@gmail.com
```

