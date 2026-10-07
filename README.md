# mootmaker-android

A native Android app, a second frontend for the same API as `mootmaker-webapp`.

**Status:** milestones M0 to M8 are built: sign up, sign in and reset your password; your agenda,
room availability, meeting details and person calendars; add, edit, cancel and respond to meetings,
with live updates; settings, avatars and deleting your account. Admin screens (M9) are still
webapp-only. The plan is [`designs/android-app.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/android-app.md)
in the hub repository.

## Layout

| Path | What |
|---|---|
| `app/` | The Android app: Compose screens, navigation, ViewModels |
| `data/` | Config loader, Cognito client (SRP), encrypted token storage, Apollo client and queries |
| `testing/` | Fakes shared by every test layer, notably `FakeBackend` (config, Cognito and GraphQL in OkHttp) |
| `scripts/cloud-setup.sh` | Installs the Android SDK in a cloud session and warms Gradle |
| `.github/workflows/pr-checks.yml` | Build, lint, unit, Robolectric and screenshot tests, plus an emulator job |
| `.github/workflows/acceptance.yml` | On a PR labelled `run-acceptance`: runs `release-build.yml` with a throwaway key, then `smoke.yml` against production |
| `.github/workflows/release-build.yml` | Called by mootmaker-release's `release.yml`: builds and signs the release APK once, runs the acceptance suite and then `smoke/sign-up-lifecycle.yaml` against it in a fresh ephemeral environment, uploads it |
| `.github/workflows/smoke.yml` | Called by `release.yml`: a Maestro flow with the release APK against a named environment. Production gets the read-only demo sign-in (`smoke/demo-sign-in.yaml`); test gets sign up with a real code, use, delete account (`smoke/sign-up-lifecycle.yaml`) |
| `app/src/androidTest/.../acceptance/` | The acceptance suite, by use case, against a real environment's fixture users and, for sign-up and reset, real emailed codes. Excluded from pr-checks |
| `email-helper/` | A small Node server the workflows start on the runner: it hands real Cognito codes from `mootmaker-email-testing`'s queue to the emulator (through `adb reverse tcp:8787 tcp:8787`) and to Maestro |

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
