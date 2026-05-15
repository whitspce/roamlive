# Roam Live

Android IRL streaming app.
Built to be able to be good enough to go live straight away from your phone with most of the features you would need.

Features:
- Chat panel which is just for the streamer so they can read it while streaming. Integration for twitch + kick chats.
- Stealth mode for discretion in public (Screen goes black but stream continues. Haptic pulses in case you forget you're streaming)
- BRB screen with customisable text + auto camera and mic mute.
- Predictive thermal management with auto bitrate degradation before phone throttles
- No google play services required, no telemetry, no analytics, private and open source by design. The app only ever checks to see if there's a new version available.

Built on [RootEncoder] for the streaming pipeline; [Jetpack Compose] for UI.

[RootEncoder]: https://github.com/pedroSG94/RootEncoder
[Jetpack Compose]: https://developer.android.com/jetpack/compose

## Website + download

[**roamlive.app**](https://roamlive.app)

## Status

Active development. v0.1.x is feature-complete for a very basic v1 launch to be released for testing.

## Build from source

Standard Android Studio project. Kotlin 2.0, AGP 8.7, Gradle 8.10. Min SDK 29
(Android 10), target 35.

```bash
./gradlew :app:assembleDebug
```

## License

[GPL-3.0-or-later](LICENSE). Phase 2 will integrate a port of the BELABOX
`srtla` cellular bonding client, which is itself GPL-3 — the project is
GPL-3 from the start for that reason.
