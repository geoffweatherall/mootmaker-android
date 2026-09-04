# mootmaker-android

A native Android app — a second frontend for the same API as `mootmaker-webapp`.

**Not started.** This repository is a placeholder.

## When work begins here

- **Read [`../mootmaker/docs/development/architecture.md`](https://github.com/geoffweatherall/mootmaker/blob/main/docs/development/architecture.md)
  first**, then `../mootmaker-api`'s README. The API contract and the auth flow are the same; only
  the frontend differs.
- **Write a design document before any code.** A second frontend raises real questions — how much of
  the use-case catalogue applies, whether the acceptance suite pattern transfers, how the schema is
  shared with a third consumer — and those are worth settling first.
- **The schema is currently mirrored by hand** in each frontend. A third consumer strengthens the
  case for sharing it as a versioned artifact; see `../mootmaker/designs/archive/graphql-schema-sharing.md`.
- **`../mootmaker/docs/reference/use-cases.md` is deliberately client-agnostic**, but not yet tagged
  per-frontend — some cases are webapp-specific in ways that will need untangling.

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
