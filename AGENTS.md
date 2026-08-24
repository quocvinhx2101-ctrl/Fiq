# Repository working agreement

These instructions apply to the entire FIQ repository.

## Git is the source of truth

- Start every change by inspecting `git status --short`, the current branch, and the recent log.
- Preserve existing and unrelated user changes. Never discard them to make a task easier.
- Put every coherent, completed change in Git. Read-only investigation does not need a commit.
- Keep commits small and atomic: one feature, fix, refactor, documentation update, or infrastructure change per commit.
- Use an imperative Conventional Commit subject such as `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `build:`, or `chore:`.
- Do not leave WIP commits on shared branches. At handoff, report the commit SHA, checks run, push state, and any intentionally uncommitted files.
- Push verified commits to `origin`. Never force-push, rewrite published history, or amend a commit that may already be shared.
- Never use destructive Git commands to remove work unless the user explicitly requests the exact operation.

## Branches

- Keep `main` releasable.
- For non-trivial work, create a focused branch named `feat/<topic>`, `fix/<topic>`, `docs/<topic>`, or `chore/<topic>`.
- Direct commits to `main` are acceptable only when the user explicitly requests them or for repository bootstrap and small maintenance changes.
- Rebase or merge only after checking for upstream changes. Do not resolve conflicts by silently choosing one side.

## Validation before commit

Run checks proportional to the changed surface:

- Java/backend/domain: `./gradlew spotlessApply ci` or the narrower affected module tests while iterating, followed by the full check before merging.
- Spark job: `./gradlew :fiq-spark-job:shadowJar` plus its tests.
- UI: from `fiq-ui`, run `npm test` and `npm run build`; run Playwright for changed user flows.
- Compose/container changes: run `docker compose config` and smoke-test the affected service when practical.
- Documentation-only changes: run `git diff --check` and verify commands, paths, and links against the repository.

Do not commit or push known failing work unless the commit is an explicitly requested reproducible test fixture and is clearly documented.

## Repository hygiene

- Never commit credentials, tokens, passwords, private keys, populated `.env` files, or secret values. Store only secret references and safe examples.
- Do not commit dependency caches, IDE state, runtime databases, logs, coverage output, Playwright artifacts, `node_modules`, or Gradle build directories. Extend `.gitignore` when a new tool creates local output.
- Dependency lockfiles and wrapper files are source-controlled and must be updated intentionally.
- Generated UI assets under `fiq-server/src/main/resources/META-INF/resources` are a tracked exception because Quarkus serves the production SPA. Rebuild and commit them together with the UI source that produced them.
- Flyway migrations are immutable after publication. Add a new migration instead of editing an applied migration.

## FIQ invariants

- FIQ must not gain a build-time or runtime dependency on Floe.
- Never mutate `_delta_log` files directly. All maintenance mutations go through supported Delta or catalog APIs.
- Preserve workspace isolation, backend RBAC enforcement, auditability, idempotency, capability checks, and the one-operation-per-scheduled-run contract.
- Unknown or unsupported Delta features must fail closed or make the table read-only, never guess execution support.
