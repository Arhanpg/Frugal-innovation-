# FrugalCCTV

An old Android phone becomes a smart CCTV camera with direct Android-to-Android streaming.

## Architecture

**Camera:** Camera2/WebRTC capture → fast local frame analysis → Google ML Kit person classification → Android alert.

**Viewer:** local UDP camera discovery → direct TCP signaling → WebRTC receive → Compose live view.

**Cloud:** none. Supabase is removed from the runtime. Video and security alerts are not sent to a cloud service.

## Features

- Camera mode and Viewer mode in one Android app.
- Room-code pairing.
- Automatic LAN camera discovery.
- Direct TCP signaling for WebRTC SDP/ICE and alerts.
- WebRTC video stays peer-to-peer.
- 640×360 at 20 FPS capture for low latency on older phones.
- Google ML Kit person classification.
- Fast per-frame motion analysis.
- Camera movement/repositioning detection.
- Sudden darkening detection.
- Possible lens obstruction detection.
- High-priority Android security notifications.
- Optional audible alarm.
- Foreground camera service.
- Kotlin + Jetpack Compose.

## Pairing

1. Put both Android phones on the same Wi-Fi/LAN.
2. Start Camera mode on the old phone and choose a room code.
3. Start Viewer mode on the second phone and enter the same room code.
4. The viewer listens for the camera's LAN beacon, opens the direct signaling connection, negotiates WebRTC, and displays the video.

If the router blocks client-to-client traffic or broadcast discovery, automatic discovery cannot work. The architecture is intentionally cloud-free; the appropriate fallback is a manually entered LAN address or a VPN that places both devices on the same reachable network.

## Detection pipeline

The camera has two independent paths:

1. **Fast path:** a tiny 24×14 luma sample is analyzed on every video frame for movement and tamper signals. This path does not wait for ML.
2. **ML path:** Google ML Kit runs asynchronously on a 256×144 image approximately every 180 ms when the previous inference has completed.

This prevents AI inference from blocking camera capture or WebRTC.

## Important network limitation

A completely third-party-free Internet CCTV system cannot universally connect two phones behind arbitrary NAT/firewalls. Direct host ICE works when the devices can directly reach one another, especially on the same LAN. Internet-wide connectivity requires network infrastructure such as a VPN, port forwarding, STUN/TURN, or a signaling service. This build deliberately chooses the independent LAN-first architecture rather than silently adding a paid cloud dependency.

## Build

The project targets Android API 35, uses JDK 17, Kotlin/Compose, WebRTC SDK, and Google ML Kit. GitHub Actions is the authoritative Android build check.
