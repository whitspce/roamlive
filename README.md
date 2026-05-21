# Roam Live

IRL streaming app built for Andriod only.
Easy enough to immediately go live from your phone but feature rich enough to include most of what you would need for IRL streaming.

Features:
- Chat panel (Twitch + Kick) just for the streamer so they can read chat while streaming.
- Stealth mode for discretion in public (Screen goes black but stream continues)
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
