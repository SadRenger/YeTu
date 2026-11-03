<div align="center">

# YeTu 🐇

**A minimalist disk-fill / storage stress test tool for Android**

[![License: MIT](https://img.shields.io/badge/License-MIT-C96F2E.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-29%2B-C96F2E.svg)](https://android-arsenal.com/api?level=29)
[![Size](https://img.shields.io/badge/APK-2.4MB-C96F2E.svg)](../../releases)
[![Release](https://img.shields.io/badge/Release-v1.0-C96F2E.svg)](../../releases/tag/v1.0)

English | [简体中文](README.md)

</div>

Fill your device storage down to a configurable free-space target with one tap — for testing how apps behave under **low-storage / disk-full conditions**: failed downloads, broken saves, crash recovery, etc.

## Features

- **Closed-loop filling**: measures free space after every chunk, converges precisely to your reserved target (default 100 MB, enforced minimum 100 MB)
- **Live status**: current free space, write progress, target reserve at a glance, with manual refresh
- **Fine-tuning**: free / occupy any number of MB at any time to simulate dynamic storage changes
- **Stop anytime**: interrupt filling with one tap, already-written data is kept
- **One-tap cleanup**: instantly restore disk space; leftover fill files from abnormal exits are detected on next launch
- **Safety-first**: first-launch disclaimer, enforced minimum reserve

## Screenshots

(TODO)

## Download

Grab the APK from [Releases](../../releases), or build from source:

```bash
git clone https://github.com/SadRenger/YeTu.git
cd YeTu
gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK Platform 37, Gradle 9.6+ (or just open it in Android Studio).

## How to Use

1. Launch the app and accept the disclaimer
2. Enter the reserved space (MB) and tap **Start**; tap the red **Stop** button anytime (data is kept)
3. Use **Free / Occupy** to fine-tune the remaining space
4. Tap **Clean Up** when done to restore disk space

## Technical Highlights

- Pure Kotlin, **zero third-party dependencies**, ~2.4 MB APK
- Writes to app-internal storage (same partition as shared storage), requires **no permissions**
- Closed-loop write algorithm: re-measures free space after each chunk, absorbing background storage fluctuations automatically
- minSdk 29 (Android 10) and above

## Why not an adb script?

That works too (this project started as one!), but:

- No computer, cable, or USB debugging needed — QA can use it right after installing the APK
- Freeing space is an instant truncate, no rewriting
- Interactive fine-tuning (free/occupy any MB) for dynamic-storage test scenarios

## License

[MIT License](LICENSE)

## Disclaimer

This tool is for development and testing purposes only. The author is not responsible for any data loss or damage caused by its use.
