# mootmaker-android

A native Android app, a second frontend for the same API as `mootmaker-webapp`.

**Status:** at parity with the webapp: every milestone, M0 to M10, is built. Sign up, sign in and
reset your password; your agenda, room availability, meeting details and person calendars; add,
edit, cancel and respond to meetings, with live updates; settings, avatars and deleting your
account; and, for admins, managing rooms and people. Each release attaches the signed APK to its
[mootmaker-release GitHub Release](https://github.com/geoffweatherall/mootmaker-release/releases/latest).
The plan was [`designs/android-app.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/android-app.md)
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
| `testing-strategy.md` | The test layers, what goes in each, and how failures reach a cloud session |
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

## Caching and live updates

Home, Meeting Details, Calendar and Room Availability draw from one in-memory store of what the app
has seen (`data/.../cache/WorkspaceStore.kt`): the reference data (you, people, rooms, the bookable
window), days, and meetings looked up by id. Going back to something seen shows it at once; the
full spinner shows only for what the app has never held, and the slim bar while something shown is
refetched. The AppSync `daysInvalidated` channel keeps it honest, by the webapp's rules: a named date
goes stale and is refetched if shown, everything goes stale after a reconnect (which is what returning
from the lock screen causes), and a response in flight when its date changed is fetched again. A
resume refetches only what is shown and more than five minutes old. This device's own writes
invalidate what they change, and signing out empties the store. The design, and why it is not
Apollo's normalized cache, is
[`designs/android-cache.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/android-cache.md).

## Errors

Every screen shows an error one of two ways, with the shared components in
`app/src/main/kotlin/com/mootmaker/app/ui/Errors.kt`. Use them; do not draw a red `Text`.

1. **Nothing to show yet** (the first load failed): `LoadFailed(message, onRetry)`, the message and
   Try again filling the screen.
2. **Every other error not about one field** (a failed refresh, a refused or failed save, response,
   cancel or admin change): `ErrorBanner(messages, onDismiss, onRetry)`.
   - It sits **above** the scrolling content, inside the Scaffold, as
     `Column { ErrorBanner(...); content with Modifier.weight(1f) }`, never inside the scroll, so it
     cannot scroll out of view (#35).
   - Every message in full, one per line; it grows to a third of the screen's height and then
     scrolls within itself.
   - Dismissible (the screen's ViewModel clears the error), and **Try again** when the error is a
     failed refresh. It is a polite live region, so TalkBack announces it.
   - A screen with several sections (Settings) still has one banner, each message prefixed with its
     section's title.
3. **Errors inside a dialog stay in the dialog.**
4. **A field's own problem stays on the field** (`isError` and `supportingText`), for what the app
   checks before calling the API.

The design is
[`designs/android-error-display.md`](https://github.com/geoffweatherall/mootmaker/blob/design/android-error-display/designs/android-error-display.md).

## Configuration and environments

The app reads `https://www.mootmaker.com/mobile-config.json` (production) or
`https://www.<env>.mootmaker.com/mobile-config.json`, written by the webapp's `deploy.sh`, and caches
the last good copy. Keys: `GRAPHQL_API_URL`, `COGNITO_USER_POOL_ID`, `COGNITO_ANDROID_CLIENT_ID`,
and optionally `DEMO_USER_EMAIL` and `DEMO_USER_PASSWORD`; unknown keys are ignored. To point the
app at another environment, tap the version on the About screen seven times and enter its name.

Read [`AGENTS.md`](AGENTS.md) before doing work here.
