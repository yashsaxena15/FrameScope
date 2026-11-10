# FrameScope Privacy Policy

_Last updated: 10 November 2026_

FrameScope is an on-device performance overlay. **It does not collect, store, transmit or share any personal data.**

## What the app accesses

To display its overlay, FrameScope reads performance information **on your device only**:

- frame timestamps of the app currently on screen (from Android's SurfaceFlinger service),
- CPU load and frequencies, memory usage, and temperatures,
- the package name of the app currently in the foreground, to know which app's frames to measure.

This information is shown on screen and kept in memory only while the overlay runs. It is never written to a server, logged to a file, or sent anywhere.

## No internet access

FrameScope does **not** request the Android `INTERNET` permission, so it is technically unable to send data off your device.

## Permissions

| Permission | Why it is needed |
|---|---|
| Display over other apps | To draw the overlay on top of games |
| Foreground service (special use) | To keep the overlay running while you play |
| Notifications | To show the "overlay running" notification with Stop / Reset buttons |
| Shizuku | To read performance data that Android only exposes to ADB (shell) |

## Device probe

The optional **Device probe** screen creates a diagnostic text report on your device. It is only shared if you choose to, using the Share or Copy buttons.

## Children

FrameScope is not directed at children and collects no data from anyone.

## Changes

Any changes to this policy will be published in this file in the project repository.

## Contact

Please open an issue at https://github.com/yashsaxena15/FrameScope/issues.
