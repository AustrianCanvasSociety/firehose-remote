# Firehose Remote

A minimal, open-source, hand-rolled Android remote for fire TV. Buttons and
nothing else.

## What it is

The stock fire TV remote app is bloated by UI choice, not by protocol
requirement. The device underneath accepts plain key events over its own local
network API, and nothing obliges us to reproduce the feed around them. This app
is the remote that results: press Scan, pick your TV, enter the PIN the TV
shows, and drive it.

No suggested content. No watch-next rails. No links out to other apps. No
accounts, no telemetry, no analytics.

**Distribution target is F-Droid**, not Google Play. That shapes the code: the
Gradle wrapper is committed (F-Droid builds from source in their own
environment), there are zero runtime dependencies, and there is no AndroidX, no
Compose, no OkHttp, and no coroutines — built-in platform APIs only.

## Status

Four phases, each ending in something visible on a real phone.

| Phase | Deliverable | State |
|---|---|---|
| 1 — It finds your TV | Scan, list, and PIN-pair with a fire TV on the LAN | Shipped |
| 2 — It drives your TV | Every button moves the TV, one tile per D-pad press | Shipped |
| 3 — It survives real use, and ships | Auto-wake on a sleeping TV, plus the F-Droid work | Shipped |
| 4 — It is safe to hand to strangers | Press safety, clear errors, any text size, screen-reader support | Released (v0.1.3) |

Phase 1 shipped: the app discovers fire TVs on the local network, lists them,
pairs with the PIN shown on the TV, and persists the token so a relaunch skips
the scan. It has been exercised end-to-end against real hardware.

## Build and run

```bash
./gradlew --offline help           # offline once a first build has cached Gradle
./gradlew --offline assembleDebug  # debug APK
./gradlew testDebugUnitTest        # JVM unit tests (protocol layer)
./gradlew --offline installDebug   # install to a connected device
adb shell am start -n io.github.austriancanvassociety.firehoseremote/.ui.MainActivity
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`, and the
release APK at `app/build/outputs/apk/release/app-release.apk` — signed when a
local `keystore.properties` is present, unsigned otherwise. The release build is
shrunk with R8, using the rules in `app/proguard-rules.pro`; renaming is
deliberately left off, so a stack trace from a user's device names the class and
method that failed without needing a mapping file.

**Toolchain:** AGP 8.11.0, Kotlin 1.9.25, `compileSdk 36`, `targetSdk 36`,
`minSdk 24`. JDK 17 or 21 — both clear AGP's floor. `buildToolsVersion`
is deliberately left unpinned.

## Tests

`./gradlew testDebugUnitTest` — JVM tests, no device and no Android runtime
required.

The protocol package is the testable seam:
it imports neither `android.*` nor `java.net.*`, and a test enforces that, so
the protocol layer runs on a plain JVM.

The tests are not decoration. The measured constants — the **220 ms** gap
between `keyDown` and `keyUp`, the `"OK"`-means-retry rule when verifying a PIN,
waking on a socket timeout **or** a refused connection — were each
*earned* by something that failed first. They look arbitrary. They are not, and
a later edit that "cleans them up" without knowing why would break the remote in
ways that are hard to trace. The tests are how the reason survives the code.

Everything touching real hardware — TLS acceptance, actual discovery, the TV
responding — is verified by hand on the device.

## Notes for reviewers

Two things look like defects and are not, both consequences of talking to a LAN
device that presents a self-signed certificate:

- **TLS certificate verification is disabled.** The TV's certificate is issued
  to a hostname while the app connects to an IP literal, so there is nothing
  valid to verify against.
- **`android:usesCleartextTraffic="true"` is required.** The wake call is plain
  HTTP to port 8009 and lives inside the recovery path; removing it makes a
  sleeping TV silently fail to wake.

## Source

`https://github.com/AustrianCanvasSociety/firehose-remote`

To build a release APK from a clean checkout of this repository:

```bash
git clone https://github.com/AustrianCanvasSociety/firehose-remote.git
cd firehose-remote
./gradlew assembleRelease testDebugUnitTest
```

The first build needs network access to download Gradle and the Android
dependencies; after that, `--offline` works. The APK is unsigned unless a
`keystore.properties` is present.

## License

MIT — see `LICENSE`.
