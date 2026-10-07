# mootmaker-android

A native Android app, a second frontend for the same API as `mootmaker-webapp`.

**Status:** milestone M1 (sign in and see your day) is being built. The app signs in with Cognito
and shows your Today/Tomorrow agenda; features it doesn't have yet open the webapp. The plan is [`designs/android-app.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/android-app.md)
in the hub repository.

## Layout

| Path | What |
|---|---|
| `app/` | The Android app: Compose screens, navigation, ViewModels |
| `data/` | Config loader, Cognito client (SRP), encrypted token storage, Apollo client and queries |
| `testing/` | Fakes shared by every test layer, notably `FakeBackend` (config, Cognito and GraphQL in OkHttp) |
| `scripts/cloud-setup.sh` | Installs the Android SDK in a cloud session and warms Gradle |
| `.github/workflows/pr-checks.yml` | Build, lint, unit, Robolectric and screenshot tests, plus an emulator job |

## Building

JDK 21 and an Android SDK (`ANDROID_HOME`; `scripts/cloud-setup.sh` installs one) are needed.

```bash
./gradlew :app:assembleDebug :app:lintDebug :data:testDebugUnitTest :app:verifyRoborazziDebug
```

Screenshots of key screens are committed under `app/src/test/screenshots/` and verified in CI. After
an intended visual change, re-record them with `./gradlew :app:recordRoborazziDebug` and commit the
PNGs.

The GraphQL schema is downloaded at build time from the `@mootmaker/schema` npm package, pinned in
`data/build.gradle.kts`. Emulator tests run only in GitHub Actions.

## Configuration and environments

The app reads `https://www.mootmaker.com/mobile-config.json` (production) or
`https://www.<env>.mootmaker.com/mobile-config.json`, written by the webapp's `deploy.sh`, and caches
the last good copy. Keys: `GRAPHQL_API_URL`, `COGNITO_USER_POOL_ID`, `COGNITO_ANDROID_CLIENT_ID`,
and optionally `DEMO_USER_EMAIL` and `DEMO_USER_PASSWORD`; unknown keys are ignored. To point the
app at another environment, tap the version on the About screen seven times and enter its name.

Read [`AGENTS.md`](AGENTS.md) before doing work here.
