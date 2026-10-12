# mootmaker-android

The native Android app: Kotlin, Jetpack Compose, Apollo Kotlin, a second client of the same API as the web app.

**Read [README.md](README.md) first**, then [docs/architecture.md](docs/architecture.md) for the rules every
screen follows and [testing-strategy.md](testing-strategy.md) for what goes in each test layer. Keep them true
in the same pull request as any change that affects them.

## Working here

- **The API is the contract.** Its operations and error codes are in mootmaker-api's `docs/api-reference.md`.
  The schema comes from the `@mootmaker/schema` npm package at the version pinned in `data/build.gradle.kts`;
  never copy it in by hand. A new field starts with a schema release from mootmaker-api.
- **Match the web app's behaviour.** The use cases (`../mootmaker/docs/reference/use-cases.md`) are tagged
  **[All frontends]** or **[Webapp-specific]**, and each all-frontends case links its Android test. When the
  two clients differ, say why in the use case.
- **Meetings, days and reference data are read through `WorkspaceStore`**, which the live channel keeps
  current; a copy held in a ViewModel would not be. Writes go through a repository, which invalidates what
  it changed. Settings and the admin lists load their own data when their screen opens.
- **Errors use `Errors.kt`** (`LoadFailed`, `ErrorBanner`); never draw a red `Text`.
- **Times from the API are zone-less local date-times.** Parse them as `LocalDateTime`, never `Instant` or
  `ZonedDateTime`.
- **Tests find elements by text and content description**, never test tags, so every icon-only control
  needs a content description.
- **Emulator tests run only in GitHub Actions.** A cloud session builds debug variants only, with
  `scripts/cloud-setup.sh`, and never handles the release key.
- **Acceptance runs on a pull request labelled `run-acceptance`**
  (`gh api repos/geoffweatherall/mootmaker-android/issues/<n>/labels -f 'labels[]=run-acceptance'`). It must
  be a `pull_request` event, because AWS trusts this repository's token only then. It creates and tears
  down its own `and-acc` environment.
- **Real emailed codes come only through `email-helper/`.** Every test that signs up uses a fresh identity
  from it and deletes the account it creates.
- **CI failures arrive as check-run annotations**, from `.github/scripts/summarise-failures.py` (tests) and
  `gradle-errors.sh` (compile and build). Logs and artifacts can't be downloaded from a cloud session.

---

## Project-wide rules

This repository is part of **Mootmaker**. The hub repository, checked out as a sibling at
`../mootmaker`, holds what applies everywhere:

- How work is done: [`../mootmaker/docs/process/README.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/process/README.md).
  Read it before any non-trivial work.
- How the system fits together: [`../mootmaker/docs/overview/`](https://github.com/geoffweatherall/mootmaker/tree/main/docs/overview).
- What to do when something is wrong: [`../mootmaker/docs/runbooks/`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/runbooks/README.md).

The short version:

- Work of any real size starts with a **design** (`../mootmaker/designs/`); a bug or small change starts
  with a **GitHub issue in this repository**, so `Closes #N` works.
- Everything lands on a **branch** through a **pull request**. Reading the diff is the review; merging is
  the approval.
- **A green acceptance run against a real deployed environment** is the definition of working.
- **`test` and `production` change only through a release** (`release.yml` in mootmaker-release). Use an
  ephemeral environment for your own work, and tear it down when you finish.
- **Say what something costs in AWS before creating it.** Under NZ$1 one-off, go ahead and state it;
  more, or anything recurring, ask first.
- **Keep documentation and comments true and current.** If a change makes a document wrong, fixing it is
  part of the change. Describe what is, not how it got here; a comment says what and why.
- **Verify against reality, not your own output**, and **say what actually happened**, including
  failures and skipped steps.

Something not installed? [`../mootmaker/tools/workstation/check.sh`](https://github.com/geoffweatherall/mootmaker/blob/main/tools/workstation/check.sh).

`CLAUDE.md` in this repository is a symlink to this file.
