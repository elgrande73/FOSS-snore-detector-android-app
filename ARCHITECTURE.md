# System & Software Architecture Documentation (arc42 Aligned)

**Application:** Snore Detector for Android (FOSS / F-Droid Distribution)  
**Package:** `com.aistudio.snoredetector.afkwd`  
**Target Platform:** Android 7.0 (API Level 24) through Android 15 / 16 (API Level 36)

---

## 1. System Context

The application runs entirely on-device in a sandboxed, zero-telemetry environment. It interfaces directly with Android HAL audio endpoints (Internal Microphones, USB Audio interfaces, Bluetooth SCO/BLE peripherals) to capture, evaluate, and store acoustic sleep data.

### System Context Diagram

```mermaid
graph TD
    User([User / Sleeper])
    
    subgraph AndroidDevice ["Android Device Host Environment"]
        App["Snore Detector Application<br/>(com.aistudio.snoredetector.afkwd)"]
        OS["Android OS / Framework APIs<br/>(AudioManager, AudioRecord, Room SQLite)"]
        Mic["Microphone Hardware<br/>(Built-in Mic, USB Audio, BT Headset/Mask)"]
        Speaker["Media Playback Engines<br/>(Podcasts, Music Players, Audiobooks)"]
        Storage["Internal App Storage<br/>(Room DB, WAV Audio Clips)"]
    end
    
    Companion(["Smartwatch / Gadgetbridge<br/>(Optional Bluetooth Peripheral)"])
    F_Droid["F-Droid / FOSS Repository<br/>(Distribution & Package Signing)"]
    
    User -->|Configures & Starts Session| App
    App -->|Requests Audio Stream| OS
    OS -->|Captures PCM 16kHz Audio| Mic
    Speaker -.->|Emits Playback Status| OS
    OS -.->|AudioPlaybackCallback| App
    App -->|Persists Events & WAVs| Storage
    App -->|Dispatches Snore Notifications| OS
    OS -.->|Mirrors Notifications| Companion
    F_Droid -.->|Delivers Standalone APK| User

    classDef boundary fill:#1E293B,stroke:#475569,stroke-width:2px,color:#F8FAFC;
    classDef component fill:#0F172A,stroke:#38BDF8,stroke-width:1.5px,color:#F8FAFC;
    classDef actor fill:#334155,stroke:#94A3B8,stroke-width:1.5px,color:#F8FAFC;
    class AndroidDevice boundary;
    class App,OS,Mic,Speaker,Storage component;
    class User,Companion,F_Droid actor;
```

**Context Characteristics:**
- **No Remote Backend / Cloud**: There is **no cloud server, analytics ingestion endpoint, or remote AI API**.
- **No Third-Party Tracker SDKs**: Relies strictly on standard Android Open Source Project (AOSP) APIs and Jetpack libraries.
- **Companion Notification Forwarding**: Compatible with Gadgetbridge for vibration feedback on connected smartwatches without proprietary companion apps.

---

## 2. Application Building Blocks (Decomposition View)

The application adheres to modern Android Architecture (MVVM + Foreground Service + Unidirectional Data Flow).

```mermaid
graph TB
    subgraph UI_Layer ["Presentation Layer (Jetpack Compose & Material 3)"]
        MainActivity["MainActivity<br/>(Compose Viewport & Permissions)"]
        DashboardTab["DashboardTab<br/>(Live Meters & Real-time Graph)"]
        HistoryTab["HistoryTab<br/>(Timeline, Waveform Playback, ZIP Export)"]
        SettingsTab["SettingsTab<br/>(DSP Thresholds & Hardware Selectors)"]
        GuideTab["GuideTab<br/>(Acoustic Help & Device Placement)"]
    end

    subgraph ViewModel_Layer ["State & Orchestration Layer"]
        SnoreViewModel["SnoreViewModel<br/>(StateFlows, SharedPreferences, Export IO)"]
    end

    subgraph Service_Layer ["Core Service & Audio Capture Engine"]
        Service["SnoreDetectionService<br/>(Foreground Service + WakeLock)"]
        InputMgr["AudioInputManager<br/>(HAL Enumeration & SCO/USB Routing)"]
        MediaDet["MediaPlaybackDetector<br/>(AudioPlaybackCallback Evaluator)"]
    end

    subgraph DSP_Layer ["Digital Signal Processing (DSP) Engine"]
        Analyzer["SnoreAnalyzer<br/>(RMS, ZCR, Band Energy, Low-Freq Ratio)"]
        FFTEngine["FFT<br/>(1024-point Radix-2 Cooley-Tukey)"]
        WavOut["WavWriter<br/>(PCM 16-bit RIFF Wave Exporter)"]
    end

    subgraph Data_Layer ["Data Persistence & Storage Layer"]
        Repo["SnoreRepository<br/>(Data Coordination & Logging Facade)"]
        RoomDB["AppDatabase (Room SQLite v3)"]
        SnoreDao["SnoreDao<br/>(snore_events CRUD)"]
        ErrorLogDao["ErrorLogDao<br/>(system_error_logs CRUD)"]
        DiskFiles["Local Filesystem Cache<br/>(context.filesDir/snore_recordings/*.wav)"]
        ExportMgr["AudioExportManager<br/>(Storage Access Framework & ZipOutputStream)"]
    end

    MainActivity --> DashboardTab & HistoryTab & SettingsTab & GuideTab
    DashboardTab & HistoryTab & SettingsTab & GuideTab --> SnoreViewModel
    SnoreViewModel --> Repo
    SnoreViewModel -.->|Observes Singletons| Service
    Service --> InputMgr
    Service --> MediaDet
    Service --> Analyzer
    Analyzer --> FFTEngine
    Service --> WavOut
    WavOut --> DiskFiles
    Service --> Repo
    Repo --> SnoreDao & ErrorLogDao
    SnoreDao & ErrorLogDao --> RoomDB
    SnoreViewModel --> ExportMgr
    ExportMgr --> DiskFiles & RoomDB

    classDef ui fill:#1e1b4b,stroke:#818cf8,color:#ffffff;
    classDef vm fill:#0f172a,stroke:#38bdf8,color:#ffffff;
    classDef srv fill:#14532d,stroke:#4ade80,color:#ffffff;
    classDef dsp fill:#701a75,stroke:#f472b6,color:#ffffff;
    classDef data fill:#312e81,stroke:#a5b4fc,color:#ffffff;
    
    class MainActivity,DashboardTab,HistoryTab,SettingsTab,GuideTab ui;
    class SnoreViewModel vm;
    class Service,InputMgr,MediaDet srv;
    class Analyzer,FFTEngine,WavOut dsp;
    class Repo,RoomDB,SnoreDao,ErrorLogDao,DiskFiles,ExportMgr data;
```

### Component Responsibilities & Interfaces

| Component | Technology | Primary Responsibility | Important Interfaces |
| :--- | :--- | :--- | :--- |
| **`MainActivity`** | Compose / AndroidX | Single-activity container, dynamic permission orchestrator (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`). | Compose UI hierarchy, `ActivityResultContracts` |
| **`SnoreViewModel`** | ViewModel / Coroutines | Exposes UI state, manages `SharedPreferences` persistence for DSP sliders, coordinates SAF export intents. | `StateFlow`, `viewModelScope`, `SnoreRepository` |
| **`SnoreDetectionService`** | Foreground Service | Maintains `AudioRecord` read loop, orchestrates WakeLock, controls notification channel, integrates DSP results. | `AudioRecord.read()`, `NotificationManager` |
| **`AudioInputManager`** | Android HAL API | Probes physical microphone endpoints, initiates Bluetooth SCO / `setCommunicationDevice`, handles fallback to built-in mic. | `AudioManager.getDevices()`, `AudioDeviceInfo` |
| **`MediaPlaybackDetector`**| Android Audio API | Identifies active music/podcast playback on `STREAM_MUSIC` to suspend triggers during sleep-listening. | `AudioPlaybackCallback`, `isMusicActive()` |
| **`SnoreAnalyzer`** | Pure Kotlin DSP | Performs frame-by-frame 1024-point time-domain and frequency-domain metric extraction and threshold evaluation. | `analyze(shortSamples, config): AnalysisResult` |
| **`FFT`** | In-place Algorithm | Cooley-Tukey Radix-2 Fast Fourier Transform computing discrete frequency magnitudes across 512 frequency bins. | `fft(real[], imag[])` |
| **`WavWriter`** | Java I/O | Encodes 16-bit 16kHz PCM frames into RIFF/WAVE header and data chunks. | `saveWavFile(File, sampleRate, List<ShortArray>)` |
| **`AppDatabase` / `SnoreDao`** | Room / SQLite v3 | Local offline database storing individual snore events with full acoustic metrics and diagnostic error logs. | Room `@Dao`, `Flow<List<SnoreEvent>>` |
| **`AudioExportManager`**| Java Zip / SAF | Streams database records and audio files into standardized ZIP and CSV formats via `DocumentUri`. | `ZipOutputStream`, `FileProvider` |

---

## 3. Audio & Signal Processing Pipeline

The detection pipeline processes raw audio in discrete non-overlapping frames of 1024 samples (64ms at 16kHz).

```mermaid
flowchart TD
    subgraph Hardware_Capture ["1. Hardware Ingestion"]
        HW["Physical Microphone<br/>(Built-in / USB / BT SCO)"]
        AR["AudioRecord Native Buffer<br/>• 16,000 Hz, 16-bit Mono PCM<br/>• AudioSource.VOICE_RECOGNITION<br/>• AudioSession AEC & NS Active"]
        HW --> AR
    end

    subgraph Frame_Chunking ["2. Frame Processing (Every 64ms)"]
        Frame["ShortArray(1024)<br/>• 1024 samples = 64 ms window<br/>• Buffer Size: 2,048 bytes"]
        Norm["Float Normalization<br/>• s[i] = raw[i] / 32768.0f"]
        AR -->|Blocking Read 1024 samples| Frame
        Frame --> Norm
    end

    subgraph Feature_Extraction ["3. Feature Extraction (SnoreAnalyzer)"]
        RMS["RMS & Sound Volume (dB)<br/>• rms = sqrt(sum(s²)/N)<br/>• dB = 20·log10(rms) + 120"]
        ZCR["Zero Crossing Rate (ZCR)<br/>• Count sign transitions / N"]
        FFT_Op["1024-point Fast Fourier Transform<br/>• Bin Width: 16000 / 1024 = 15.625 Hz<br/>• 512 Discrete Frequency Bins"]
        Band["Core Band Energy (100–1000 Hz)<br/>• Mean magnitude of Bins 6..64"]
        Ratio["Low-Freq Energy Ratio (≤ 500 Hz)<br/>• Energy(Bins 0..32) / TotalEnergy"]
        
        Norm --> RMS
        Norm --> ZCR
        Norm --> FFT_Op
        FFT_Op --> Band
        FFT_Op --> Ratio
    end

    subgraph Threshold_Logic ["4. Logical Multi-Criteria Evaluation"]
        Eval{"Classification Rule:<br/>dB ≥ Min_dB (Mandatory)<br/>AND (ZCR ≤ Max_ZCR)<br/>AND (BandEnergy ≥ Min_Band)<br/>AND (LowFreqRatio ≥ Min_Ratio)"}
        MediaCheck{"Media Playing?<br/>(isMusicActive / Callback)"}
        
        RMS & ZCR & Band & Ratio --> Eval
        MediaCheck -->|Playback Active| Suspend["Suppress Trigger<br/>(MEDIA STANDBY)"]
        MediaCheck -->|No Media| Eval
    end

    subgraph Event_Integration ["5. Temporal Integration & Incident Logging"]
        Timer["Continuous Snore Accumulator<br/>(Accumulates 64ms frames)"]
        DurCheck{"Duration ≥ minDurationSeconds<br/>(Default: 1.0s)"}
        DBWrite[("Insert SnoreEvent into Room DB")]
        WavWrite["WavWriter: Save .WAV clip<br/>(Buffered Audio Frames)"]
        Notif["Dispatch Snore Notification / Vibration"]

        Eval -->|Snore Frame True| Timer
        Timer --> DurCheck
        DurCheck -->|Met| DBWrite & WavWrite & Notif
        Eval -->|Snore Frame False| ResetTimer["Finalize Incident / Reset Accumulator"]
    end

    classDef hw fill:#0f172a,stroke:#38bdf8,color:#fff;
    classDef feat fill:#312e81,stroke:#818cf8,color:#fff;
    classDef logic fill:#4c1d95,stroke:#c084fc,color:#fff;
    classDef action fill:#064e3b,stroke:#34d399,color:#fff;
    
    class HW,AR,Frame,Norm hw;
    class RMS,ZCR,FFT_Op,Band,Ratio feat;
    class Eval,MediaCheck,Suspend logic;
    class Timer,DurCheck,DBWrite,WavWrite,Notif,ResetTimer action;
```

### Acoustic Pipeline Technical Specifications

| Parameter | Value / Implementation | Architectural Justification |
| :--- | :--- | :--- |
| **Sampling Rate** | 16,000 Hz (16 kHz) | Nyquist frequency is 8 kHz; human snoring spectra are predominantly concentrated below 1.5 kHz. Minimizes CPU and battery usage. |
| **Channel Config** | `AudioFormat.CHANNEL_IN_MONO` | Single channel reduces buffer sizes and memory bandwidth by 50% compared to stereo. |
| **Audio Format** | `AudioFormat.ENCODING_PCM_16BIT` | Standard uncompressed linear PCM supported across 100% of Android devices. |
| **Frame / Window Size** | 1024 samples (64.0 milliseconds) | Power-of-two window size required for radix-2 Cooley-Tukey FFT. |
| **Spectral Resolution** | 15.625 Hz per FFT bin ($16000 / 1024$) | Enables precise bin-level isolation for the 100–1000 Hz band (Bins 6–64) and ≤500 Hz low-band (Bins 0–32). |
| **Acoustic Preprocessing**| `AudioSource.VOICE_RECOGNITION` + `AcousticEchoCanceler` + `NoiseSuppressor` | Strips speaker feedback loop and ambient background hiss while retaining snore transients. |
| **Latency per Frame** | ~64 ms read interval + <1 ms compute | Real-time classification on low-end ARM cores without buffer underrun or audio frame drops. |

---

## 4. Overnight Recording Runtime Sequence

This sequence illustrates the primary use-case: continuous overnight monitoring across app backgrounding, lock-screen transitions, snore detection, and final export.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as MainActivity / UI
    participant VM as SnoreViewModel
    participant Srv as SnoreDetectionService
    participant HAL as AudioRecord / AudioInputManager
    participant DSP as SnoreAnalyzer
    participant DB as AppDatabase (Room)
    participant Storage as Internal Filesystem

    User->>UI: Taps "Start Monitoring"
    UI->>UI: Check RECORD_AUDIO & Notification Permissions
    UI->>VM: startService(context)
    VM->>Srv: ContextCompat.startForegroundService(Intent)
    
    activate Srv
    Srv->>Srv: Acquire PowerManager.PARTIAL_WAKE_LOCK (12hr limit)
    Srv->>Srv: Post Ongoing Notification (NOTIFICATION_ID=54321, Channel: MONITORING)
    Srv->>HAL: Initialize AudioRecord (16kHz, Mono, 1024 samples)
    Srv->>HAL: Attach AcousticEchoCanceler & NoiseSuppressor
    Srv->>HAL: AudioRecord.startRecording()
    
    User->>UI: Locks phone / Screen turns off
    UI->>UI: Activity onStop() / onDestroy()
    Note over Srv,HAL: Foreground Service keeps audio thread executing continuously
    
    loop Every 64ms (Audio Read Loop)
        HAL->>Srv: read(shortBuffer, 0, 1024) [Blocking Read]
        Srv->>DSP: analyze(shortBuffer, config)
        activate DSP
        DSP->>DSP: Compute RMS, dB, ZCR, FFT, Band Energy, Low-Freq Ratio
        DSP-->>Srv: Return AnalysisResult(isSnoring=true/false)
        deactivate DSP
        
        alt Continuous Snoring Detected >= minDurationSeconds
            Srv->>DB: SnoreDao.insertEvent(SnoreEvent)
            opt Audio Clip Recording Enabled
                Srv->>Storage: WavWriter.saveWavFile(File, 16000, bufferList)
            end
            opt Notification Alert Enabled
                Srv->>Srv: Dispatch Alert Notification (Sound/Vibrate)
            end
        end
    end

    User->>UI: Wakes phone, unlocks and taps "Stop Monitoring"
    UI->>Srv: sendCommand(ACTION_STOP_SERVICE)
    Srv->>HAL: AudioRecord.stop() & release()
    Srv->>HAL: Release AcousticEchoCanceler & NoiseSuppressor
    Srv->>Srv: Release WakeLock & stopForeground(STOP_FOREGROUND_REMOVE)
    Srv->>Srv: stopSelf()
    deactivate Srv
    
    UI->>VM: Query latest session records
    VM->>DB: SnoreDao.getAllEvents()
    DB-->>UI: Display full overnight timeline & statistics
```

---

## 5. Android Lifecycle & Background Execution Architecture

```mermaid
stateDiagram-v2
    [*] --> Idle : App Launched
    
    state Foreground_Active {
        Idle --> PermissionCheck : Tap Start
        PermissionCheck --> ServiceStarting : Permissions Granted
        PermissionCheck --> Idle : Permission Denied / Error Logged
    }

    state Background_Monitoring {
        ServiceStarting --> ActiveRecording : startForeground() + WakeLock
        
        state ActiveRecording {
            [*] --> Monitoring_DSP : Audio Capture Running
            Monitoring_DSP --> MediaStandby : isMusicActive == true
            MediaStandby --> Monitoring_DSP : isMusicActive == false (Auto Resume)
            Monitoring_DSP --> IncidentAccumulating : Snore Threshold Met
            IncidentAccumulating --> Monitoring_DSP : Snore Ends (< minDuration)
            IncidentAccumulating --> LoggedEvent : Snore Duration >= minDuration
            LoggedEvent --> Monitoring_DSP : Event & WAV Written
        }
        
        ActiveRecording --> ActiveRecording : Screen Off / Doze Mode Active
        ActiveRecording --> DeviceSwitchFallback : External Mic Disconnected (USB/BT)
        DeviceSwitchFallback --> ActiveRecording : Rerouted to Built-in Mic
    }

    ActiveRecording --> ServiceStopping : Tap Stop / 12hr Auto Limit Reached
    ServiceStopping --> Idle : AudioRecord Released + WakeLock Freed
```

### Background Execution Guarantee Mechanics

1. **Foreground Service Declaration**:
   Declared with `android:foregroundServiceType="microphone"`. Binds directly to a persistent system status-bar notification, preventing Android OS low-memory killer (LMK) eviction.
2. **Partial WakeLock Management**:
   `PowerManager.WakeLock` (`PowerManager.PARTIAL_WAKE_LOCK`, tag: `SnoreDetector:WakeLock`) is acquired at session launch with an absolute safety timeout of 12 hours (`12 * 60 * 60 * 1000L`). This prevents CPU sleep when the screen turns off.
3. **Hardware Routing Resiliency**:
   If a Bluetooth headset or USB adapter disconnects during the night, `AudioInputManager` traps the routing change and falls back to `TYPE_BUILTIN_MIC` without terminating the foreground service.

---

## 6. Machine Learning Architecture Assessment

### Architectural Confirmation
- **Status in Source Code:** **NO MACHINE LEARNING MODEL PRESENT**.
- **Underlying Engine:** Pure deterministic on-device **Digital Signal Processing (DSP)**.

```mermaid
graph LR
    subgraph DSP_Architecture ["Deterministic DSP Engine (Implemented)"]
        Raw["Raw PCM Audio<br/>(16kHz, 16-bit Mono)"]
        TimeDomain["Time-Domain Analysis<br/>• RMS Amplitude & dB SPL<br/>• Zero-Crossing Rate (ZCR)"]
        FreqDomain["Frequency-Domain (FFT)<br/>• 100–1000 Hz Band Energy<br/>• ≤ 500 Hz Low-Freq Ratio"]
        Rules["Logical Multi-Threshold Classifier<br/>(Configurable via UI Sliders)"]
        Output["Snore Classification<br/>(isSnoring = True/False)"]
        
        Raw --> TimeDomain & FreqDomain
        TimeDomain & FreqDomain --> Rules
        Rules --> Output
    end

    subgraph Future_ML_Placeholder ["ML/Neural Network Classification (Not Implemented / UNKNOWN)"]
        ML_Model["TFLite / ONNX / PyTorch Mobile Model<br/>(UNKNOWN — Not Present in Codebase)"]
        Remote_ML["Cloud / Gemini AI Classification<br/>(UNKNOWN — Strictly 0 Remote Calls)"]
    end

    classDef actual fill:#064e3b,stroke:#34d399,color:#fff;
    classDef future fill:#334155,stroke:#94a3b8,stroke-dasharray: 5 5,color:#cbd5e1;
    
    class Raw,TimeDomain,FreqDomain,Rules,Output actual;
    class ML_Model,Remote_ML future;
```

**Architectural Rationale for DSP over ML:**
- **Zero Ingestion Latency & Predictability**: DSP operates with sub-millisecond execution times without matrix tensor allocations.
- **Battery Conservation**: An all-night (8–10 hour) neural network inference loop on mobile hardware causes battery drain and thermal throttling; standard FFT + ZCR consumes minimal CPU resources.
- **Transparency & User Control**: Users can adjust individual physical acoustic parameters (RMS dB threshold, ZCR rumble cap, frequency ratios) rather than treating detection as an opaque black box.

---

## 7. Data Architecture & Lifecycle

```mermaid
graph TD
    subgraph Volatile_Memory ["1. Volatile In-Memory Streams (Short-Lived)"]
        PCM_Buffer["Raw PCM Buffer: ShortArray(1024)<br/>• Retention: ~64 milliseconds<br/>• Cleared immediately on next read"]
        Recent_Frames["Circular Audio Cache: List<ShortArray><br/>• Holds last N seconds for incident clipping<br/>• Cleared when snore incident terminates"]
        Live_Timeline["_currentSessionData: StateFlow<List<AmplitudePoint>><br/>• 500ms decibel decimation for UI graph"]
    end

    subgraph Persistent_Storage ["2. Local App-Private Storage (Persistent)"]
        Room_DB[("Room SQLite DB: snore_detector.db<br/>• Table: snore_events (Timestamp, dB, RMS, ZCR, Spectral Ratios)<br/>• Table: system_error_logs (Stack traces, diagnostics)")]
        WAV_Files["Filesystem WAV Clips<br/>• Location: context.filesDir/snore_recordings/<br/>• Format: SnoreDetector_YYYY-MM-DD_HH-mm-ss.wav<br/>• RIFF 16-bit mono 16kHz PCM"]
        Prefs["SharedPreferences: snore_detector_prefs<br/>• User threshold values & toggles"]
    end

    subgraph Export_Boundary ["3. Export Boundary (Storage Access Framework)"]
        ZIP_Archive["Exported ZIP Package<br/>• snoring_events.csv<br/>• audio/SnoreDetector_*.wav"]
        CSV_File["Exported CSV File<br/>• Timestamp, Datetime, Duration, dB, Metrics"]
        TXT_Log["Exported Error Log (.txt)"]
    end

    PCM_Buffer --> Recent_Frames
    PCM_Buffer --> Live_Timeline
    Recent_Frames -->|On Snore Incident Logged| WAV_Files
    PCM_Buffer -->|Metric Extraction| Room_DB
    
    Room_DB --> ZIP_Archive & CSV_File
    WAV_Files --> ZIP_Archive
    Room_DB --> TXT_Log

    classDef vol fill:#1e1b4b,stroke:#818cf8,color:#fff;
    classDef per fill:#064e3b,stroke:#34d399,color:#fff;
    classDef exp fill:#701a75,stroke:#f472b6,color:#fff;
    
    class PCM_Buffer,Recent_Frames,Live_Timeline vol;
    class Room_DB,WAV_Files,Prefs per;
    class ZIP_Archive,CSV_File,TXT_Log exp;
```

### Data Element Lifecycle Matrix

| Data Item | Storage Location | Retention Policy | Leaves Device? |
| :--- | :--- | :--- | :--- |
| **Raw PCM Frame (64ms)** | In-Memory (`ShortArray`) | **Volatile**: Overwritten every ~64ms. | **Never** |
| **Live Timeline Decibels**| StateFlow (`List<AmplitudePoint>`) | **Session Scope**: Cleared when a new recording starts. | **Never** |
| **Snore Incidents (Metrics)**| SQLite (`snore_events` table) | **Persistent**: Retained until user deletes manually or clears app data. | **Only via User-Initiated SAF Export** |
| **Audio Incident Clips (.wav)**| App Internal Storage (`filesDir/snore_recordings/`)| **Persistent**: Retained until user deletes records or toggles saving off. | **Only via User-Initiated SAF / Share Intent** |
| **System Error Logs** | SQLite (`system_error_logs` table) | **Persistent**: Anonymized local logs for on-device diagnostics. | **Only via User-Initiated Share/Export** |
| **DSP Preferences** | `SharedPreferences` | **Persistent**: Maintained across app updates. | **Never** |

---

## 8. Privacy & Security Architecture

```mermaid
graph TD
    subgraph Android_OS_Boundary ["Android OS Sandbox Boundary"]
        subgraph Permissions_Enclosure ["Permission Gating"]
            PermRecord["android.permission.RECORD_AUDIO"]
            PermFG["android.permission.FOREGROUND_SERVICE_MICROPHONE"]
            PermBT["android.permission.BLUETOOTH_CONNECT"]
        end

        subgraph Local_App_Sandbox ["Snore Detector Private Sandbox (/data/data/com.aistudio.snoredetector.afkwd/)"]
            Service["SnoreDetectionService (android:exported=false)"]
            DB["Room SQLite Database (No external access)"]
            AudioFiles["Private Internal Audio Clips (No world-readable permissions)"]
            NoNet["INTERNET Permission: OMITTED (0 Network Access)"]
        end
        
        subgraph Export_Intermediary ["Secure Sharing Boundary"]
            FileProv["androidx.core.content.FileProvider<br/>(android:exported=false, grantUriPermissions=true)"]
        end
    end

    UserApp["External Sharing Targets<br/>(Email, Files App, Cloud Backup chosen by User)"]

    PermRecord & PermFG & PermBT --> Service
    Service --> DB & AudioFiles
    AudioFiles --> FileProv
    FileProv -->|User-Explicit Content URI Grant| UserApp

    classDef sandbox fill:#0f172a,stroke:#38bdf8,stroke-width:2px,color:#fff;
    classDef perm fill:#1e1b4b,stroke:#818cf8,color:#fff;
    classDef sec fill:#064e3b,stroke:#34d399,color:#fff;
    classDef ext fill:#334155,stroke:#94a3b8,color:#fff;
    
    class Local_App_Sandbox sandbox;
    class PermRecord,PermFG,PermBT,NoNet perm;
    class Service,DB,AudioFiles,FileProv sec;
    class UserApp ext;
```

### Security & Privacy Verifications
- **Complete Air-Gap Isolation**: The `android.permission.INTERNET` permission is **explicitly omitted from `AndroidManifest.xml`**. The app cannot transmit data to the network.
- **Service Isolation**: `SnoreDetectionService` is configured with `android:exported="false"`, preventing other applications from binding to or hijacking the audio recording pipeline.
- **Sandboxed Storage**: All SQLite data and `.wav` audio snippets are stored in internal private app storage (`context.filesDir`), inaccessible to non-root applications.
- **Export via Content URIs**: Shared audio files use standard Android `FileProvider` with temporary single-target URI grants (`FLAG_GRANT_READ_URI_PERMISSION`).

---

## 9. Deployment Architecture

```mermaid
graph TD
    subgraph Build_Distribution ["Distribution & Build Pipeline"]
        Source["Kotlin / Gradle DSL Source Code"]
        KSP["Kotlin Symbol Processing (Room Compiler)"]
        AGP["Android Gradle Plugin (Target SDK 35 / Min SDK 24)"]
        APK["Universal Offline APK / AAB"]
        FDroid["F-Droid / GitHub Releases"]
        
        Source --> KSP & AGP
        KSP & AGP --> APK
        APK --> FDroid
    end

    subgraph Device_Runtime ["End-User Android Device Target"]
        OS_Runtime["Android OS 7.0 - 16 (API 24 - 36)"]
        AppPkg["Installed App Sandbox (com.aistudio.snoredetector.afkwd)"]
        AudioHAL["Hardware Audio Subsystem"]
        
        FDroid -->|Installs| AppPkg
        AppPkg -->|Binds Foreground Service| OS_Runtime
        OS_Runtime -->|Polls Hardware Mic| AudioHAL
    end

    classDef build fill:#1e1b4b,stroke:#818cf8,color:#fff;
    classDef runtime fill:#0f172a,stroke:#38bdf8,color:#fff;
    
    class Source,KSP,AGP,APK,FDroid build;
    class OS_Runtime,AppPkg,AudioHAL runtime;
```

---

## 10. Failure, Resource, & Degradation Architecture

```mermaid
graph TD
    subgraph Failure_Scenarios ["Runtime Failure Scenarios"]
        Fail_Mic["Microphone Ingestion Fails<br/>(Device disconnected or hardware busy)"]
        Fail_Buffer["Audio Buffer Underflow / Zero-Frames<br/>(All samples read == 0)"]
        Fail_Media["Media Playback Active<br/>(Podcast / Music playing on device)"]
        Fail_Time["12-Hour Session Exceeded<br/>(Limit Duration Guard)"]
        Fail_DB["Room Database Write Error<br/>(Storage full / SQLite locked)"]
    end

    subgraph Recovery_Mechanisms ["Defensive Recovery & Resilience Paths"]
        Rec_Fallback["AudioInputManager Fallback:<br/>Reroutes automatically to Built-in Phone Mic"]
        Rec_Diag["Diagnostic Counter:<br/>Logs non-zero sample diagnostics to ErrorLogger"]
        Rec_Standby["MediaPlaybackDetector:<br/>Transitions to MEDIA STANDBY without stopping AudioRecord"]
        Rec_Shutdown["Graceful Auto-Stop:<br/>Flushes active incident, releases WakeLock, saves DB"]
        Rec_Logger["ErrorLogger Catch-All:<br/>Wraps DAO operations in CoroutineScope with fallback"]
    end

    Fail_Mic --> Rec_Fallback
    Fail_Buffer --> Rec_Diag
    Fail_Media --> Rec_Standby
    Fail_Time --> Rec_Shutdown
    Fail_DB --> Rec_Logger

    classDef fail fill:#7f1d1d,stroke:#f87171,color:#fff;
    classDef rec fill:#064e3b,stroke:#34d399,color:#fff;
    
    class Fail_Mic,Fail_Buffer,Fail_Media,Fail_Time,Fail_DB fail;
    class Rec_Fallback,Rec_Diag,Rec_Standby,Rec_Shutdown,Rec_Logger rec;
```

---

## 11. Comprehensive arc42 Architectural Assessment

### 1. Introduction and Goals
- **Documented from Source:** The system is an on-device Android application designed to capture, classify, log, and export nocturnal snoring events using real-time DSP.
- **Primary Goals:** Low power consumption during 8–12 hour monitoring sessions, zero cloud dependencies, complete privacy, audio hardware versatility (Built-in, USB, Bluetooth SCO), and clean export capabilities (ZIP/CSV).

### 2. Constraints
- **Technical Constraints:** Android API 24+ (Android 7.0 Nougat and newer).
- **Distribution Constraints:** Distributed through F-Droid and FOSS channels; must not depend on proprietary Google Play Services APIs or closed-source tracking binaries.
- **Hardware Constraints:** Must operate across low-end mobile CPUs without thermal throttling during overnight sleep cycles.

### 3. Context and Scope
- **Scope:** Pure on-device acoustic analysis. No external cloud servers or user accounts. Audio ingestion is strictly local from the phone’s microphone subsystem.

### 4. Solution Strategy
- **MVVM Architecture:** Jetpack Compose for declarative UI, `SnoreViewModel` for UI state representation, `SnoreRepository` for Room database coordination.
- **Decoupled Background Execution:** Dedicated `SnoreDetectionService` (Foreground Service with `PARTIAL_WAKE_LOCK`) maintains continuous audio capture and DSP independent of UI lifecycle state.
- **Deterministic DSP Classification:** Radix-2 FFT and multi-feature evaluation (RMS dB, ZCR, Band Energy, Low Frequency Spectral Ratio) replace heavy machine learning inference models.

### 5. Building Block View
- **Presentation:** Compose UI (`MainActivity`, `DashboardTab`, `HistoryTab`, `SettingsTab`, `GuideTab`).
- **Orchestration:** `SnoreViewModel`, `AudioInputManager`, `MediaPlaybackDetector`.
- **Processing:** `SnoreAnalyzer`, `FFT`, `WavWriter`.
- **Persistence:** `AppDatabase`, `SnoreDao`, `ErrorLogDao`, `AudioExportManager`.

### 6. Runtime View
- **Lifecycle Guarantees:** Continuous overnight recording backed by foreground notification and 12-hour WakeLock safety limit.
- **Coexistence Pipeline:** `MediaPlaybackDetector` registers system audio playback callbacks to suspend snoring evaluation while podcasts or music play, automatically resuming when media stops.

### 7. Deployment View
- Single-module APK (`app`) compiled with Kotlin DSL, Android Gradle Plugin, and KSP for Room SQLite metadata generation.

### 8. Crosscutting Concepts
- **Error Handling & Diagnostics:** `ErrorLogger` provides a unified diagnostic catch-all mechanism logging device specifications, OS build info, and stack traces to a dedicated SQLite table (`system_error_logs`).
- **Theming & Design:** Material Design 3 dynamic color scheme supporting system-wide Light and Dark themes with edge-to-edge system bar insets.

### 9. Architecture Decisions Record (ADR Summary)
1. **Decision: Pure DSP vs. Machine Learning**
   - *Rationale:* Deterministic DSP (RMS dB + ZCR + FFT) uses less CPU/memory and avoids battery drain over 8–12 hours compared to continuous neural network inference, while giving users full transparency to tune thresholds.
2. **Decision: Complete Offline Isolation (No Internet Permission)**
   - *Rationale:* Total privacy for overnight bedroom audio recording; builds user trust for F-Droid distribution.
3. **Decision: Storage Access Framework (SAF) for Export**
   - *Rationale:* Eliminates risky `MANAGE_EXTERNAL_STORAGE` permissions while allowing export to user-selected locations.

### 10. Quality Requirements Evaluation
- **Battery & CPU Efficiency:** 16kHz mono sampling and 64ms frame processing keep CPU utilization minimal on modern ARM architectures.
- **Reliability:** Audio routing automatically falls back to built-in mic if external USB or Bluetooth peripherals disconnect overnight.
- **Testability:** Complete unit test suite (`MediaPlaybackDetectorTest`, `AudioInputManagerTest`, `SnoreDetectionLogicTest`, `AudioExportManagerTest`, Robolectric CUJ tests).

### 11. Risks and Technical Debt
- **Risk 1 (Device-Specific Battery Optimization):** Aggressive OEM battery killers (e.g., Xiaomi MIUI, Huawei EMUI, Samsung OneUI) may kill background processes despite the Foreground Service and WakeLock if the user does not disable battery optimization for the app.
- **Risk 2 (Microphone Gain Discrepancies):** Raw microphone input gain differs across phone hardware; relative dB calculation relies on uncalibrated Android microphone references unless tuned in settings.

### 12. Glossary of Terms
- **DSP:** Digital Signal Processing.
- **RMS (Root Mean Square):** Statistical measure of the magnitude of a varying audio signal.
- **ZCR (Zero-Crossing Rate):** The rate at which an audio signal changes from positive to zero to negative (useful for identifying low-pitch snoring rumble vs. high-frequency speech/hiss).
- **FFT (Fast Fourier Transform):** An efficient algorithm to compute the Discrete Fourier Transform (DFT), converting time-domain PCM samples into frequency-domain spectral magnitude bins.
- **SCO (Synchronous Connection-Oriented):** Bluetooth profile used for two-way voice communication and wireless microphone audio capture.
- **SAF (Storage Access Framework):** Android platform file access mechanism allowing users to save and load files without broad storage permissions.

