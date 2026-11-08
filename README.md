# PerformanceMonitorPro / FPS Lite

**FPS Lite** is a tiny (~140 KB) Android overlay that shows an accurate FPS counter,
frame-time graph, CPU, RAM and temperatures on **non-rooted** phones, using
[Shizuku](https://github.com/RikkaApps/Shizuku) for ADB-level access.

![overlay](https://img.shields.io/badge/root-not%20required-brightgreen) ![size](https://img.shields.io/badge/APK-~140%20KB-blue)

## Install

1. Install **Shizuku** and start it (Wireless debugging, `adb`, or root).
2. Download `FPSLite-x.y.z.apk` from [Releases](../../releases) and install it.
3. Open FPS Lite → **Grant Shizuku permission** → **Allow display over other apps** → **Start overlay**.

Requires Android 10+. Tested on a Poco X8 Pro Max (Dimensity 9500s, HyperOS 3 / Android 16).

## What it shows

| Overlay | Meaning |
|---|---|
| `59.7 FPS @120Hz` | Frames presented per second (last 1 s) and the display refresh rate |
| `avg 59  1% 40  .1% 39` | Average FPS, 1% low and 0.1% low over the last 10 s |
| `16.7ms  jank 4` | Latest frame time and stutter count (double-tap the overlay to reset) |
| graph | Frame time of the last 120 frames; spikes are slow frames |
| `CPU 50% 1.0\|0.6\|0.8 GHz` | CPU load and current speed of each core cluster (efficiency \| performance \| prime) |
| `RAM 8.6/11.0G 79% App 1.30G` | System RAM used / usable, and RAM of the app in front |
| `VRAM 4.5/12.0G 38%` | Virtual RAM in use (ZRAM + memory extension), optional |
| `CPU 46° GPU 46° Bat 39°` | Temperatures from Android's thermal service |

Presets: **Minimal** (FPS number), **Standard** (FPS stats + graph), **Full** (+ CPU, RAM, temps).
Tap **?** in the app for the same explanations on the phone.

## How it works

- **FPS** comes from SurfaceFlinger's per-layer frame timestamps
  (`dumpsys SurfaceFlinger --latency`, *actual present* time). Each frame is
  counted exactly once; the buffer is read every 250 ms so no frames are missed
  at 120 Hz. The game's `SurfaceView (BLAST)` layer is selected automatically.
- A small **helper process** runs with shell privileges via Shizuku
  (`app_process` started from the APK) and calls system services' `dump()`
  directly over binder — no `dumpsys` process per sample. It talks to the app
  over a pipe. This avoids Shizuku's UserService, which crashes on many
  MediaTek phones (Shizuku issues #1198, #2561).
- The overlay is one Canvas-drawn View — no Compose, AppCompat or Hilt — and
  allocates nothing per frame.

**GPU** load/frequency is available as an *experimental* option only: on
MediaTek Dimensity 9000+ the GPU's own microcontroller manages frequency and
the readable kernel events do not reflect it, and the real GPU sysfs nodes are
blocked without root.

## Build

```
./gradlew :fpscore:test :fpslite:assembleRelease
```

CI builds every push; pushing a `v*` tag publishes a signed release.
Modules: `:fpscore` (pure Kotlin parsing + FPS math, unit-tested with real
device captures), `:fpslite` (the app), `:app` (legacy PerformanceMonitorPro,
being migrated).
