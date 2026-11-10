# Greenfield scenario

The request was to build the URL shortener from scratch. It asked for create and redirect APIs, validation, collision handling, click counting, rate limiting, an OpenAPI spec, a schema and tests.

## At a glance

| | |
|---|---|
| Live runs | 3 |
| Result of each run | Stopped safely on a real build failure |
| Final code | Run 3, recovered and fixed by a human |
| Merged in | PR #8 |
| Tests after the fix | 82 passing |

## Run 1 (`2288a7b8`)

Evidence is in [greenfield-first-attempt](../greenfield-first-attempt/).

- Requirements and design were approved.
- The design asked for two new libraries, a test-only OpenAPI parser and the JaCoCo coverage plugin.
- A human reviewed both and added them to the dependency allowlist.
- The code stage was not allowed to write `pom.xml`, so the libraries were never added.
- The build failed. The run rolled back and stopped.
- The code prompt was then changed to ask for `pom.xml`.

## Run 2 (`6a6faf0e`)

Evidence is in [greenfield-second-attempt](../greenfield-second-attempt/).

- Requirements and design were approved again.
- The tests stage chained two assertions in a way that does not compile.
- This is a model mistake. Prompt tweaks would not fix it in general, so none were made.
- The build failed. The run rolled back and stopped.

## Run 3 (`475aab57`)

Evidence is in [greenfield-third-attempt](../greenfield-third-attempt/). The final evidence set is in this folder.

- Requirements and design were approved with no extra libraries.
- The code stage wrote `FixedWindowRateLimiter(int, Duration, Clock)`.
- The tests stage called `FixedWindowRateLimiter(AppProperties, Clock)`.
- The design listed methods but not constructors, so the two stages guessed differently.
- The build failed. The run rolled back and stopped.

## Human recovery

Run 3 had the most complete code, so it was recovered from its failed-state tag instead of starting a fourth run.

1. **Added the missing constructor.** A one-line overload, with no other changes.
2. **Renamed 4 test classes from `*IT` to `*Test`.** Only Surefire is configured, so `*IT` classes never ran.
3. **Fixed a real database bug.** Once those tests ran, they showed that H2 upper-cased table names while Spring Data JDBC queried in lower case. The fix was a datasource setting.

After these fixes, all 82 tests passed on two separate runs.

## Orchestrator fixes found along the way

- The Copilot SDK call timed out after 60 seconds. It now uses the configured timeout.
- The code prompt now delivers `pom.xml` with the right parent.
- The secret scan now applies its config rule only to config files, after a false positive.
- Build failures now show the `[ERROR]` lines.

## What this shows

| Capability | Evidence |
|---|---|
| Ambiguity handling | Each run listed ambiguities with an assumption for each |
| Approval gates | Nothing moved forward without a recorded decision (`approvals.md`) |
| Policy guardrails | The dependency allowlist forced a human decision on new libraries |
| Rollback and safe-stop | Each failure rolled back cleanly. See `metrics.md` and `audit.jsonl`. |
| Human ownership | The orchestrator could not reach a clean build alone. A human finished it with full context. |

## Known gap

- A safe-stopped run cannot resume after a human fix.
- So `docs`, `policy-scan` and `release-readiness` were written by hand.
- The docs went into `url-shortener/README.md`. The other two are in `artifacts/`.
