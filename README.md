# FrugalCCTV

An old Android phone becomes a smart CCTV camera.

## Architecture

**Camera phone:** native WebRTC capture → local ML Kit person detection → foreground camera service.

**Viewer phone:** native WebRTC receive → live Compose UI.

**Cloud:** Supabase Realtime carries only WebRTC signaling messages. No video is uploaded to Supabase.

### Included features

- Camera mode + viewer mode in one Android app.
- 10-character randomly generated room codes.
- 960×540 @ 20 fps camera capture to keep an old phone's load reasonable.
- P2P WebRTC live video with built-in STUN.
- Optional TURN configuration for difficult NATs.
- On-device ML Kit object detection in streaming mode.
- Person-detected alerts when protection is armed.
- Local high-priority Android alert notification.
- Optional audible local alarm.
- Foreground camera service compliant with modern Android camera-service rules.
- Jetpack Compose UI.
- MVVM-oriented separation of domain/data/service/UI concerns.

## Important limitation

A generic object detector is not a complete "bad event" detector. v1 deliberately uses a clear, explainable rule: **person detected while protection is armed**. A future project phase can add a custom LiteRT/TFLite model trained on your actual home (for example: person + package + smoke/fire + fall detection).

## Supabase setup

1. Create a Supabase project.
2. Leave public Realtime channels enabled for v1.
3. Copy the project URL and publishable key into the app setup screen.
4. For hardened deployments, migrate the room channel to private Realtime channels and add RLS authorization.

Supabase's current Kotlin SDK provides the Realtime channel/broadcast APIs used here. See the official Kotlin and Realtime documentation.

## Android Studio

Use Android Studio **Quail 4 / 2026.1.4** (or compatible) with JDK 17. The repository uses AGP 9.4.0 and Gradle 9.6.0.

### Build in Android Studio

Open the repository as an existing Gradle project, let Android Studio sync, then use **Build → Generate App Bundles or APKs → Generate APKs**. The debug APK is written to:

`app/build/outputs/apk/debug/app-debug.apk`

### Command-line build

Install Gradle 9.6.0 and Android SDK Platform 37 + Build Tools 36.0.0, then run:

```bash
gradle assembleDebug
```

GitHub Actions performs the same build automatically and uploads the APK as `frugal-cctv-debug-apk`.

## Real-world reliability

Android requires the camera foreground service to be started while the app is visible and with camera permission. The app therefore starts protection from the Camera setup screen instead of pretending it can silently start camera access after reboot.

Some networks cannot establish a direct WebRTC path with STUN alone. The UI accepts TURN servers for that case.
