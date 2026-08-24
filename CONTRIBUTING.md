# Contributing to FIQ

FIQ treats Git history as part of the product. A clean history makes maintenance behavior,
database changes, and safety decisions reviewable long after implementation.

## Development workflow

1. Synchronize and inspect the repository:

   ```bash
   git status --short
   git log --oneline -5
   git fetch origin
   ```

2. Create a focused branch for non-trivial work:

   ```bash
   git switch -c feat/short-topic
   ```

3. Implement one coherent change and add or update its tests and documentation.
4. Run the checks appropriate to the changed modules.
5. Review the staged diff, then create an atomic Conventional Commit:

   ```bash
   git diff --check
   git diff --staged
   git commit -m "feat: describe the user-visible outcome"
   ```

6. Push the branch without rewriting shared history:

   ```bash
   git push -u origin HEAD
   ```

Use `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `build:`, or `chore:` as the commit type.
Avoid mixed-purpose commits and generic messages such as `update` or `changes`.

## Validation matrix

| Changed area | Required baseline |
| --- | --- |
| Java/domain/server | `./gradlew spotlessApply ci` |
| Spark artifact | `./gradlew :fiq-spark-job:shadowJar` and module tests |
| React UI | `cd fiq-ui && npm test && npm run build` |
| User flow | Relevant Playwright tests at supported viewports |
| Compose/container | `docker compose config` and an affected-service smoke test |
| Documentation only | `git diff --check` and manual command/link verification |

Run the broader suite when a change crosses module boundaries. Do not push a knowingly broken
branch as completed work.

## Safety and compatibility

- Do not commit secrets or populated environment files. Configuration must refer to external
  secret providers, environment variables, or safe example values.
- Never edit Delta transaction-log files directly. FIQ mutations must use supported Delta or
  catalog APIs.
- Do not change an already released Flyway migration; create the next versioned migration.
- Keep `main` releasable and preserve compatibility contracts unless the change includes an
  explicit migration and upgrade note.
- Treat unsupported or unknown Delta capabilities as read-only.

Generated production SPA assets are intentionally tracked under
`fiq-server/src/main/resources/META-INF/resources`. Commit those assets in the same change as the
corresponding `fiq-ui` source.

## Pull-request handoff

Describe the outcome, affected modules, tests run, migration or compatibility impact, and any
remaining risk. The final worktree should be clean; if it is not, identify every remaining file
and why it was intentionally left uncommitted.
