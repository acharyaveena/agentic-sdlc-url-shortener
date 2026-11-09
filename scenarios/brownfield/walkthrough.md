# Brownfield scenario: add link expiry to the existing URL shortener

**Request:** add a configurable time-to-live to existing links, return `410 Gone` once a link has expired,
and eventually clean up expired links that are no longer useful.

This scenario needed one live orchestrator run (`23df9175-6527-459c-b9f9-f473221de25a`), but that run
surfaced the project's most significant structural finding: **the orchestrator never gives the design,
code or tests stages the real repository source for a brownfield change** — `StageContext.upstreamOutputs`
only ever carries the text output of previous stages, never actual files. For greenfield this is harmless
(there is nothing to collide with). For brownfield it produced two separate, increasingly serious failures
in a single run.

## Requirements and impact analysis
Requirements were approved cleanly: 14 acceptance criteria, 10 ambiguities each with a stated assumption
(default TTL, cleanup cadence, what happens to an expired link's click count and rate-limit state, and so
on — see `artifacts/requirements.md`). Impact analysis correctly identified the affected module, the schema
change (`expires_at` column) and the API change (`ttlSeconds` in, `expiresAt` out), both flagged for
approval as the project's rules require (`artifacts/impact-analysis.md`).

## Design: two re-plans needed
The design stage was not given the real `url-shortener` source. Its first attempt assumed JPA (the project
actually uses Spring Data JDBC), assumed Flyway/Liquibase (the project actually uses a single `schema.sql`
applied with `CREATE TABLE IF NOT EXISTS` via `spring.sql.init.mode=always`), and guessed wrong class names
— the design even said outright "I wasn't given the existing code."

The first re-plan attempt failed for a mundane reason: the corrective feedback was passed to the CLI as
`--reason=$fb` without quoting, so Spring Boot's argument tokenizer split it on spaces and only the single
word "Corrections" reached the model (confirmed in the audit log). The design came back almost unchanged
(revision 2).

The second re-plan, with the feedback properly quoted (`--reason='$fb'`) and containing concrete facts
about the real code (the actual persistence style, the actual `AppProperties`/`RateLimit` nesting
convention, the actual `schema.sql` approach, the actual class names), produced a much better design
(revision 3). It was honest about which details it still wasn't sure of — explicitly asking the reviewer to
verify `LinkNotFoundException`, `RateLimitExceededException`, the request/response class names and the API
path — and two of its own earlier guesses (the API path and the response class name) turned out to be
wrong, which the reviewer caught before approving. Revision 3 was approved; the orchestrator's maximum of
three revisions per stage was reached in the process.

## Code and tests: the real failure
With a corrected, approved design in hand, the code and tests stages both ran and were marked `SUCCEEDED`
by the orchestrator's own exit gates. The actual output was not usable:

- **Ten new files at the wrong, flat package** `com.veenaacharya.shortener` (missing the real `.link`,
  `.web`, `.config` subpackages), several of which duplicated real classes outright: `Link.java`,
  `LinkRepository.java`, `LinkService.java`, `ApiExceptionHandler.java`, `CreateLinkRequest.java`, and a
  `ClockConfig.java` that would have collided with the real `config.ClockConfig` Spring bean.
- **`pom.xml` was rewritten in place** and silently dropped `spring-boot-starter-validation` and the
  `<description>` tag — neither mentioned anywhere in the approved design.
- **`schema.sql` was rewritten in place**, not edited additively: it renamed `long_url` to `original_url`
  (which would have broken every real query), widened `short_code` from `VARCHAR(16)` to `VARCHAR(32)`, and
  dropped both existing table constraints (`uk_links_short_code`, `ck_links_click_count_non_negative`).
  Only the new `ALTER TABLE ... ADD COLUMN IF NOT EXISTS expires_at` and `CREATE INDEX IF NOT EXISTS` lines,
  which the design explicitly asked for, were correct.
- **The code stage disobeyed its own approved design.** The design said to nest TTL settings under
  `AppProperties` (matching the real `RateLimit` convention); the code stage built a standalone
  `LinkProperties` class instead. The tests stage, a separate model call against the exact same approved
  design, got this detail right (`AppPropertiesLinkTest` targets the correct nested-class shape) — direct
  evidence that two stages can diverge even when the design they share is unambiguous.

The build failed with more than ten compile errors (`cannot find symbol: LinkNotFoundException`,
`RateLimitExceededException`, `InvalidUrlException`; `package jakarta.validation does not exist`, and so
on) because the new `ApiExceptionHandler.java` referenced the duplicated classes in the wrong package and
never imported the real ones. The run safe-stopped and rolled back; the broken state is preserved at git
tag `agent-failed/23df9175` for anyone who wants to inspect exactly what a live model produced.

## Recovery
Rather than spend another live run chasing the same problem (the root cause — no real-file context — would
not have been fixed by trying again), the feature was integrated by hand directly into the real files,
using the approved design and the generated tests' scenario coverage as reference material, not as source
to merge:

- `AppProperties` gained a nested `Link` properties group, matching the existing `RateLimit` convention.
- `Link` gained an `expiresAt` field and an `isExpired(Instant)` method, with a backward-compatible
  constructor so every existing call site and test kept working unchanged.
- `LinkRepository` gained one new atomic `@Modifying @Query` method, `deleteExpiredBefore(Instant)`.
- `LinkService.resolve()` now checks expiry (and throws a new `LinkExpiredException`, mapped to `410`) before
  incrementing the click count, so an expired link's click is never counted.
- A new `ExpiredLinkCleanupJob`, `@Scheduled`, deletes rows past their grace period.
- `schema.sql` gained only the two additive, idempotent statements the design actually asked for — the
  original `CREATE TABLE` block and its constraints are untouched.
- `pom.xml` was not touched at all; the feature needed no new dependency.

The full change is one commit, `fix(human-review): add link expiry (manual recovery of broken AI
code-gen)` (`deaebd7`), with 12 new or updated tests. `./mvnw -pl url-shortener verify` passes 94 of 94
tests; the whole-repo `./mvnw verify` passes 283 orchestrator tests plus those 94, with no regressions.

## What this demonstrates
- **Ambiguity handling and governed approval still worked:** 10 ambiguities with stated assumptions,
  schema/API changes correctly flagged, nothing proceeded without an explicit human decision
  (`approvals.md`).
- **Re-plan works, but feedback delivery is a sharp edge:** the engine and `RetryingStageRunner` correctly
  seed and pass retry feedback; the first re-plan's failure was an operator (CLI quoting) mistake, not an
  orchestrator bug. Once fixed, re-plan materially improved the design.
- **The orchestrator's own stage-success signal is not sufficient evidence of usable output.** Code and
  tests both reported `SUCCEEDED` and still produced a build that would not compile, and — more seriously —
  two files that would have silently corrupted real schema and dependency state had a reviewer not read
  every line of the diff.
- **This is the single most important risk finding of the whole project:** brownfield changes must not be
  trusted without full human review of every file the code/tests stages touch, especially files they modify
  in place rather than create. Prompting the model to "be careful" is not a fix; the underlying gap is
  architectural (no real-file context is ever passed to these stages for brownfield runs). This is recorded
  as the top entry in `docs/RISKS.md`.

## Known gap
Same as greenfield: the orchestrator has no command to resume a `SAFE_STOPPED` run after an out-of-band
fix. Docs, the policy scan and the release-readiness summary for this scenario (`artifacts/policy-scan.md`,
`artifacts/release-readiness.md`; the user-facing docs went into `url-shortener/README.md` directly) were
therefore written by hand instead of by the automated stages.
