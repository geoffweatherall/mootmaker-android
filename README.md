# mootmaker-android

A native Android app, a second frontend for the same API as `mootmaker-webapp`.

**Status:** milestone M0 (toolchain spike). The app is an empty Compose screen; there is no user
functionality yet. The plan is [`designs/android-app.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/android-app.md)
in the hub repository.

## Layout

| Path | What |
|---|---|
| `app/` | The Android app (Compose, Apollo Kotlin) |
| `scripts/cloud-setup.sh` | Installs the Android SDK in a cloud session and warms Gradle |
| `.github/workflows/pr-checks.yml` | Build, lint, unit, Robolectric and screenshot tests, plus an emulator job |

## Building

JDK 21 and an Android SDK (`ANDROID_HOME`; `scripts/cloud-setup.sh` installs one) are needed.

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:recordRoborazziDebug
```

The GraphQL schema is downloaded at build time from the `@mootmaker/schema` npm package, pinned in
`app/build.gradle.kts`. Emulator tests run only in GitHub Actions.

Read [`AGENTS.md`](AGENTS.md) before doing work here.
