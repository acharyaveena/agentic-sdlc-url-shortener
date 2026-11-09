## Summary
Link expiry (configurable TTL, `410 Gone` on redirect after expiry, scheduled cleanup with a grace period)
was added to the existing URL shortener as a brownfield change. Requirements were approved with 14
acceptance criteria and 10 documented ambiguities. The design needed two re-plans (three revisions total,
the orchestrator's maximum) because the design stage initially had no access to the real repository code
and guessed wrong frameworks and names; the third revision, informed by detailed corrective feedback, was
approved. The code and tests stages then both ran and were marked `SUCCEEDED` by the orchestrator's own
gates, but their actual output was structurally broken — wrong package, duplicate classes, and silent
regressions in two files they rewrote in place (`pom.xml`, `schema.sql`). The feature was integrated by
hand directly into the real files instead of merging that output. `./mvnw verify` now passes for the whole
repository: 283 orchestrator tests, 94 url-shortener tests (12 new/updated for this feature).

## Validation results
- **Requirements:** reviewed and approved by a human (see `approvals.md`). 14 acceptance criteria, 10
  ambiguities each with a stated assumption (see `artifacts/requirements.md`).
- **Impact analysis:** ran once, identified the affected module (`url-shortener`), the schema change and
  the API change, both correctly flagged as needing approval (see `artifacts/impact-analysis.md`).
- **Design:** required two re-plans. Revision 1 and 2 assumed JPA, Flyway/Liquibase and wrong class names —
  none of which exist in the real codebase — because `StageContext` does not give the design stage the real
  repository files for a brownfield change. Revision 3, re-planned with explicit corrective facts about the
  real code (Spring Data JDBC, the real `AppProperties`/`RateLimit` nesting convention, the real
  `schema.sql` approach), was approved (see `approvals.md` for the full re-plan feedback).
- **Code and tests stages:** both reported `SUCCEEDED`, but produced output that does not compile against
  the real repository and, where it modified real files in place, silently dropped a dependency
  (`spring-boot-starter-validation`) and two database constraints. None of this output was merged; see
  `../walkthrough.md` for the full defect list and `agent-failed/23df9175` for the preserved evidence.
- **Build and tests (after manual recovery):** `./mvnw -pl url-shortener verify` — 94 of 94 tests pass,
  including 8 new integration tests against a fixed clock covering TTL defaults/overrides, redirect-before-
  and after-expiry, stats remaining available after expiry, legacy (never-expiring) links, and the cleanup
  job's grace-period boundary. Whole-repo `./mvnw verify` also passes (283 orchestrator tests).
- **Policy scan:** performed manually after the automated run stopped early; see `artifacts/policy-scan.md`.
  No secrets, no new dependencies, schema change matches what was actually approved.

## Risks and trade-offs
- **Brownfield code generation without real repository context is unsafe — the central finding of this
  scenario.** The design stage guessing wrong frameworks was merely inconvenient (caught and corrected via
  re-plan before anything was built). The code/tests stages regenerating whole files from scratch instead
  of modifying the real ones, and silently corrupting the two files they did edit in place, is actively
  dangerous: if a reviewer merged this without reading every line, the schema rewrite alone would have
  broken `uk_links_short_code` (duplicate short codes could then be created) and
  `ck_links_click_count_non_negative`, and the dropped `spring-boot-starter-validation` dependency would
  have made every `@Valid`/`@NotBlank` annotation in the codebase silently stop validating.
- **A tiny expiry race exists by design.** `LinkService.resolve()` reads the link, checks expiry, then
  increments the click count as a second statement; a link could theoretically expire or be deleted between
  those two steps. This trades a very narrow race window for keeping the click-count increment atomic
  (single `UPDATE ... WHERE short_code = :code`), which matters more for the concurrent-redirect guarantee
  already covered by `ConcurrentRedirectTest`. Documented here rather than engineered away.
- **Cross-stage drift happened again, even against a corrected design.** The tests stage got the
  `AppProperties.Link` nesting right; the code stage, given the same approved design, ignored it and
  created a standalone `LinkProperties` class anyway. This is the same category of defect as the greenfield
  scenario's constructor mismatch — independent model calls against one design can still disagree.
- **The orchestrator still cannot resume a `SAFE_STOPPED` run after an out-of-band fix.** Docs, the policy
  scan and this summary were written by hand, same as greenfield. Recorded in `docs/RISKS.md`.

## Limitations
- The automated pipeline did not produce usable code or tests for this scenario at all, not even a
  recoverable near-miss (contrast with greenfield's third attempt, where the generated code only needed a
  missing constructor overload). A human wrote the actual implementation and tests, using the approved
  design and the generated tests' scenario coverage as a reference, not as source to merge.
- Coverage was not measured (no JaCoCo gate in this design); test count and real execution are the evidence
  of thoroughness here.

## Recommendation
RELEASE WITH CONDITIONS: merge once a reviewer has read commit `deaebd7` (`fix(human-review): add link
expiry`) and confirmed the hand-written implementation matches the approved design's intent (TTL default 30
days, hourly cleanup with a 7-day grace period, 410 before cleanup then 404 after). No further automated
gate blocks it; `./mvnw verify` passes for the whole repository. This scenario's real value is the risk
finding above, not the feature itself — it should be cited prominently in `docs/RISKS.md` and `docs/SUMMARY.md`.
