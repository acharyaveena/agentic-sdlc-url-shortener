# Greenfield scenario: build the URL shortener

**Request:** build the URL shortener from scratch — create/redirect API, validation, collision handling,
click counting, rate limiting, an OpenAPI spec, a database schema, and tests.

This scenario needed three live runs of the orchestrator against the real Copilot models (Opus 5.5 for
requirements, impact analysis and design; Sonnet 5.5 for code, tests and docs). Each run is kept as
evidence, because each one surfaced a different real failure.

## Attempt 1 — `scenarios/greenfield-first-attempt/`
Requirements and design were approved. The design asked for a test-only OpenAPI-parsing dependency
(`io.swagger.parser.v3`) and a coverage plugin (`org.jacoco`), which were not yet on the policy allowlist.
We reviewed and explicitly approved both, adding them to `orchestrator/src/main/resources/application.yml`
as a recorded policy change. Despite that, the code stage's prompt only allowed it to write files under
`src/main/`, so it never delivered `pom.xml` at all — the build then failed because the approved dependency
was never actually added. **Fix:** the code prompt now explicitly asks for `pom.xml` with the correct
parent coordinates. Run safe-stopped and rolled back after `build-test` failed.

## Attempt 2 — `scenarios/greenfield-second-attempt/`
Same request, after the prompt fix. Requirements and design were approved again (the model doesn't remember
earlier runs; each is independent). Code and tests both succeeded, but the build failed: the tests stage
wrote `.andExpect(jsonPath("$.shortUrl").value(endsWith("/")).doesNotExist())`, which chains two assertions
onto one call and doesn't compile. This is a model mistake, not a tooling gap, and not something worth
chasing with prompt tweaks — hallucinations like this are an open-ended problem. Run safe-stopped and
rolled back.

## Attempt 3 — `scenarios/greenfield-third-attempt/`
Requirements and design were approved a third time, cleanly, no extra dependencies requested. Code and
tests both succeeded, but the build failed again: `FixedWindowRateLimiter(int, Duration, Clock)` was what
the code stage wrote, while the tests stage called `FixedWindowRateLimiter(AppProperties, Clock)` — the
design's contracts table listed methods but not the constructor, so the two stages (separate model calls)
disagreed on a detail the design hadn't pinned down. Run safe-stopped and rolled back.

### Recovery
Attempt 3's code and tests were the most complete of the three, so rather than spending more Copilot quota
on a fourth attempt, we recovered them from the orchestrator's preserved `agent-failed/<run>` git tag and
reviewed them as a human would review any pull request:

1. Added the missing `FixedWindowRateLimiter(AppProperties, Clock)` overload (a one-line addition, no
   call sites needed to change).
2. Found that the 4 integration test classes were named `*IT.java`, which Maven Surefire's default
   configuration silently ignores (Failsafe, which does run `*IT` classes, was never configured). Renamed
   them to `*Test.java` so they actually run under `./mvnw verify`.
3. With the integration tests now actually running, found a real bug: `schema.sql` creates an unquoted
   `links` table, which H2 uppercases to `LINKS` by default, but Spring Data JDBC's generated queries use a
   quoted lowercase `"links"` — a genuine mismatch that would have broken the real application, not just
   the tests. Fixed by adding `DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE` to the datasource
   URL, in both `application.yml` and each test's own hardcoded override.

All three fixes are one commit: `fix(human-review): resolve constructor mismatch, enable integration
tests, and fix H2 case-folding`. `./mvnw -pl url-shortener verify` now passes 82 of 82 tests, confirmed
stable across two separate runs.

## What this demonstrates
- **Requirement understanding and ambiguity handling:** each attempt's requirements document listed 14–30
  ambiguities with reasons and a concrete assumption for each (see `artifacts/requirements.md` in each
  attempt's folder).
- **Governed approval checkpoints:** nothing proceeded to the next stage without an explicit human decision,
  recorded in `approvals.md` with hashes and timestamps.
- **Retry, fallback and rollback:** every failed attempt is visible in `metrics.md` and `lineage.md`
  (attempt counts, retry rate, rollback rate, MTTR) and the audit log (`audit.jsonl`), not hidden.
- **Policy guardrails working as designed:** attempt 1's missing-pom problem and the dependency approval are
  both a direct result of the write-path and dependency-allowlist rules doing their job.
- **Controlled autonomy in practice:** the orchestrator took three attempts and could not produce a clean
  build on its own. A human reviewer, with full visibility into what failed and why (the preserved
  `agent-failed` tag), produced a working result in minutes. That division of labor — the orchestrator
  automates the repeatable SDLC steps and enforces governance; a human owns final quality — is the whole
  point of this project, not a fallback we had to invent.

## Known gap
The orchestrator has no command to resume a `SAFE_STOPPED` run after an out-of-band fix. Docs, the policy
scan and the release-readiness summary for this scenario (`artifacts/docs` is `url-shortener/README.md`
itself; `artifacts/policy-scan.md` and `artifacts/release-readiness.md`) were therefore written by hand
instead of by the automated stages. This is recorded as a limitation in `docs/RISKS.md`.
