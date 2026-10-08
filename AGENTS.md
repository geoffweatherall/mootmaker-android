# mootmaker-android

A native Android app — a second frontend for the same API as `mootmaker-webapp`.

**Status:** at parity with the webapp; the milestones M0 to M10 of
[`designs/android-app.md`](https://github.com/geoffweatherall/mootmaker/blob/main/designs/android-app.md)
in the hub repository are built. New work starts with its own design or issue, as anywhere in
mootmaker. [`testing-strategy.md`](testing-strategy.md) describes the test layers.

## Working here

- **Read [`../mootmaker/docs/development/architecture.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/development/architecture.md)
  first**, then `../mootmaker-api`'s README. The API contract and the auth flow are the same; only
  the frontend differs.
- **The schema is downloaded from the `@mootmaker/schema` npm package** at a pinned version (see
  `data/build.gradle.kts`), not mirrored by hand.
- **`../mootmaker/docs/reference/use-cases.md` is tagged per frontend**: every case is
  **[All frontends]** or **[Webapp-specific]**, with an "android:" slot for its test-case link.
- **Emulator tests run only in GitHub Actions.** Cloud sessions build debug variants only and never
  handle the release keystore.
- `scripts/cloud-setup.sh` sets up the Android SDK in a cloud session.
- **Acceptance runs on a PR labelled `run-acceptance`** (add it with
  `gh api repos/geoffweatherall/mootmaker-android/issues/<n>/labels -f 'labels[]=run-acceptance'`).
  It must be a `pull_request` event: AWS trusts this repository's token only then. It creates and
  tears down its own `and-acc` environment.
- **Real emailed codes come only through `email-helper/`**, which wraps `mootmaker-email-testing` on the
  runner (design Decision 6). Acceptance reaches it on the emulator's `localhost:8787` through
  `adb reverse`; the `test`-stage Maestro smoke calls it directly. Every test uses a fresh identity
  from it and deletes any account it creates.
- **Times from the API are naive local date-times** (`2026-10-07T09:00:00`, no zone). Parse them
  as `LocalDateTime`, never `Instant` or `ZonedDateTime`.
- **CI failures arrive as check-run annotations**, written by `.github/scripts/summarise-failures.py`
  (test failures) and `gradle-errors.sh` (compile and build errors). Logs and artifacts can't be
  downloaded from a cloud session.

---

## Project-wide rules

This repository is part of the **mootmaker** project. The workflow rules that apply everywhere live
in the hub repository, which you should find checked out as a sibling directory:

    ../mootmaker/docs/process/README.md

On GitHub: <https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/README.md>

**Read it before doing any non-trivial work here.** The short version:

- Work of any real size starts with a **design document** (`../mootmaker/designs/`), not with code.
- Bugs and small changes start with a **GitHub issue in this repository**, so `Closes #N` works.
- All work happens on a **branch** and lands via a **pull request**. There is no approval step —
  reading the diff is the review, merging is the approval.
- **A green acceptance run against a real deployed environment** is the definition of working — not
  a passing unit suite, and not a successful deploy.
- **Environments are `production`, `test`, or ephemeral.** `test` and `production` change only
  through `release.yml` in mootmaker-release — never `./deploy.sh` by hand. Everything else is
  ephemeral: tear down any you create, as part of finishing rather than as a tidy-up afterwards.
- **If your change makes a document wrong, fixing it is part of the change.**
- **Verify against reality, not your own output.** A script exiting zero is not evidence that the
  thing it was meant to do happened.
- **Say what actually happened.** Failing tests get reported with their output; skipped steps get
  named.

Also useful: [`../mootmaker/docs/roles/`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/roles/)
for which kind of work you are doing, and
[`../mootmaker/tools/workstation/check.sh`](https://github.com/geoffweatherall/mootmaker/blob/main/tools/workstation/check.sh)
if something is not installed.

`CLAUDE.md` in this repository is a symlink to this file.
