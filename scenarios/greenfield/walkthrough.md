# Greenfield scenario

I asked the orchestrator to build the URL shortener from scratch. The request covered create and redirect APIs, validation, collision handling, click counting, rate limiting, an OpenAPI spec, a schema and tests.

## Summary

- It took three live runs.
- Each run stopped safely on a real build failure.
- I recovered the third run's code and fixed it myself.
- The result was merged in PR #8, with 82 passing tests.
- The requirements and design files in `artifacts/` and in the attempt folders are unedited model output.

## Run 1 (`2288a7b8`)

Evidence is in [greenfield-first-attempt](../greenfield-first-attempt/).

- I approved the requirements and the design.
- The design asked for two new libraries, an OpenAPI parser for tests and the JaCoCo coverage plugin.
- I reviewed both and added them to the dependency allowlist.
- The code stage was not allowed to write `pom.xml`, so the libraries never got added.
- The build failed, and the run rolled back and stopped.
- I then changed the code prompt so it delivers `pom.xml`.

## Run 2 (`6a6faf0e`)

Evidence is in [greenfield-second-attempt](../greenfield-second-attempt/).

- I approved the requirements and the design again.
- The tests stage chained two assertions in a way that does not compile.
- That is a model mistake. I decided not to tweak prompts for it, because the next hallucination would be different.
- The build failed, and the run rolled back and stopped.

## Run 3 (`475aab57`)

Evidence is in [greenfield-third-attempt](../greenfield-third-attempt/). The final evidence set is in this folder.

- I approved the requirements and the design. No new libraries were needed.
- The code stage wrote `FixedWindowRateLimiter(int, Duration, Clock)`.
- The tests stage called `FixedWindowRateLimiter(AppProperties, Clock)`.
- The design listed methods but not constructors, so the two stages guessed differently.
- The build failed, and the run rolled back and stopped.

## How I recovered

Run 3 had the most complete code. Instead of starting a fourth run, I recovered it from its failed-state tag.

1. I added the missing constructor as a one-line overload.
2. I renamed four test classes from `*IT` to `*Test`. Only Surefire is configured, so the `*IT` classes never ran.
3. I fixed a real database bug that those tests then exposed. H2 upper-cased the table name, but Spring Data JDBC queried it in lower case. A datasource setting fixed it.

All 82 tests passed after that, on two separate runs.

## Orchestrator fixes from this scenario

- The Copilot SDK call timed out after 60 seconds. It now uses the configured timeout.
- The code prompt now delivers `pom.xml` with the right parent.
- The secret scan applies its config rule only to config files, after a false positive.
- Build failures now show the `[ERROR]` lines.

## What I took from it

- The approval gates worked. Nothing moved forward without a recorded decision (`approvals.md`).
- The dependency allowlist made me decide on the new libraries, instead of letting them in silently.
- Every failure rolled back cleanly. See `metrics.md` and `audit.jsonl`.
- The orchestrator could not reach a clean build alone. I finished it with the full failure context.

## Known gap

- A safe-stopped run cannot resume after a human fix.
- So I wrote `policy-scan.md` and `release-readiness.md` by hand, in `artifacts/`.
- The docs went into `url-shortener/README.md`.
