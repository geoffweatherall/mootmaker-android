# Testing strategy

How the Android app is tested, layer by layer. The project-wide picture, with the API's and the
webapp's layers beside these, is
[`docs/reference/testing-strategy.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/testing-strategy.md)
in the hub repository. As everywhere in mootmaker, **a green acceptance run against a real deployed
environment is the definition of working**; the layers below it exist to fail sooner and more cheaply.

## Layers

| Layer | Where | Runs against | When |
|---|---|---|---|
| Unit | `data/src/test`, and ViewModel tests in `app/src/test` | Plain JVM; fakes for the API sources | pr-checks |
| Flow (Robolectric) | `app/src/test/.../ui/*FlowTest.kt`, `*ScreenTest.kt` | The whole app, or one screen, on Robolectric with `FakeBackend` playing config, Cognito and GraphQL | pr-checks |
| Screenshots (Roborazzi) | `app/src/test/.../ui/ScreenshotTest.kt`, PNGs in `app/src/test/screenshots/` | Robolectric's native graphics; light, dark and 200% font | pr-checks (`verifyRoborazziDebug`) |
| Instrumented | `app/src/androidTest/.../app/` (not `acceptance/`) | An API 34 emulator: token encryption in the real Keystore, the app flow, and a read-only demo sign-in against a deployed environment | pr-checks `emulator` job |
| Acceptance | `app/src/androidTest/.../acceptance/` | The release APK on an emulator against a fresh ephemeral environment (`and-acc-*` on a PR, `rel-and-*` in a release), with real fixture users and real emailed codes through `email-helper/` | A PR labelled `run-acceptance`, and every release |
| Smoke (Maestro) | `smoke/*.yaml` | The signed release APK against `test` (sign up with a real code, use, delete account) and production (read-only demo sign-in) | Every release, either side of each promotion |

## What goes where

- **Acceptance is organised by use case.** Each test names the cases it covers
  (`/** F.42: ... */`), and [`use-cases.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/reference/use-cases.md)
  links each **[All frontends]** case to the test that covers it on Android. A case a real
  environment can't stage, such as a slow, unreachable or refusing backend (M.92 to M.94) or the
  system's dark setting (M.96), is covered at the flow layer instead, and linked there.
- **Every acceptance case makes what it needs** (rooms, people, meetings) with names unique to the
  run, through the real API, and checks the outcome there as well as on screen. Any account it
  signs up is deleted afterwards.
- **Screenshots cover every screen in light and dark**, and the busiest screens at Android's largest
  font size, where text must wrap rather than clip. After an intended visual change, re-record with
  `./gradlew :app:recordRoborazziDebug` and commit the PNGs.
- **Tests find elements by text and content description**, the same labels TalkBack reads, not by
  test tags.

## Accessibility

Covered by the large-font screenshots and by content descriptions on every icon-only control (the
tests depend on them). Compose's automated accessibility checks do not run under Robolectric, so
there is no automated contrast or touch-target check today, and no TalkBack walk-through has been recorded.

## Reading failures from a cloud session

Logs and artifacts can't be downloaded from a cloud session. `.github/scripts/summarise-failures.py`
writes each failed test as a check-run annotation (its message, any "Caused by", the first app frame
and the test's own line), and `gradle-errors.sh` does the same for compile and build errors. An
acceptance wait that times out says what it waited for and lists the text on screen.
