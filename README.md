## Website + download

[**roamlive.app**](https://roamlive.app)

## About Roam Live

IRL streaming app built for Andriod only. Currently in Early Alpha as it's still in active development.
Built to be easy enough to immediately go live from your phone but feature rich enough that you could stay on it for a while without needing to get a server. Specifically built for IRL streaming on Andriod.

- No Google Play services required (Great for grapheneOS), no telemetry or analytics collected. Fully open source and private by design.

Built on [RootEncoder] for the streaming pipeline; [Jetpack Compose] for UI.
[RootEncoder]: https://github.com/pedroSG94/RootEncoder
[Jetpack Compose]: https://developer.android.com/jetpack/compose

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
