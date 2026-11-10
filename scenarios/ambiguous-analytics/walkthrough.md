# Ambiguous scenario: "make analytics better"

**Request:** a deliberately vague, one-line brownfield request against the existing URL shortener —
"Make analytics better for the URL shortener." — with no detail on what "better" means, which metrics
matter, or who consumes them.

This scenario's required demonstration was requirements ambiguity handling, a design rejected at
approval, and a re-plan. All three happened. The run then went further: code and tests stages ran,
reported success, and produced a second, more subtle category of risk finding than the brownfield
scenario did.

## Requirements: ambiguity handled well
The requirements stage did not guess. It listed 10 ambiguities — what "better" means, which metrics,
who the consumer is, privacy implications of storing referrers/user agents, retention and granularity,
effect on redirect latency, whether the existing click counter stays, bot/duplicate filtering, UI vs API,
and interaction with the already-shipped link-expiry feature — each with a stated assumption and a note
that the assumptions "may be rejected at design approval." It proposed 11 concrete, testable acceptance
criteria (exact daily-series values, exact referrer ordering, bot-filter behavior, no personal data stored,
and more). This was approved on the first pass with no re-plan needed — see `artifacts/requirements.md`.

## Design: rejected and re-planned, as required
The design stage was not given the real `url-shortener` source, same structural gap as the brownfield
scenario. Its first revision assumed JPA (the real stack is Spring Data JDBC), assumed Flyway migrations
(the real approach is a single `schema.sql`), and used the wrong API prefix (`/api/links` instead of the
real `/api/v1/links`) and wrong class names (`GlobalExceptionHandler` instead of the real
`ApiExceptionHandler`), while honestly labeling all of this as assumptions needing verification.

This design was **rejected**, with a detailed reason listing the real facts: Spring Data JDBC conventions,
the real `schema.sql` approach, the real `/api/v1/links` prefix, the real class names
(`RedirectController`, `LinkService`, `ApiExceptionHandler`, `AppProperties`), the existing `ClockConfig`
bean to reuse, and a request to extend the already-shipped link-expiry `resolve()` flow rather than
replace it. The re-plan produced a revision that matched the real codebase closely: Spring Data JDBC
throughout, `schema.sql` appended additively, the real `/api/v1/links/{code}/analytics` path, and an
explicit decision to extend `LinkService.resolve` with a `ClickMetadata` overload rather than build a
parallel mechanism — with the exact trade-off stated: "if the event insert fails, the redirect fails. We
get consistency." This revision was approved.

**Operator mistake along the way:** one CLI call in the middle of this run (`--approve=...:requirements`)
was issued without repeating `--orchestrator.agent.mode=copilot`. Because each CLI invocation is a
separate JVM that reads `application.yml` fresh, the impact-analysis and design stages that ran as part of
that call silently fell back to the mock agent and produced literal "(mock output)" placeholder text. This
was caught immediately (the output was obviously templated) and fixed by re-planning impact-analysis with
the flag correctly included, which regenerated both stages for real. This is an operator error, not an
orchestrator defect — the same category of mistake as the brownfield scenario's CLI-quoting failure.

## Code and tests: success that wasn't
With an approved, accurate design in hand, the code and tests stages both ran and were marked
`SUCCEEDED`. Unlike the brownfield scenario, nothing existing was corrupted — only new files in a new
`analytics` package, and `pom.xml` was correctly left untouched (no new dependency was needed, and the code
stage recognised that). On the surface, this looked like the clean run the project had not yet had.

It was not. The approved design's central decision — extend `LinkService.resolve` with a
`ClickMetadata` overload, recording the click event in the same transaction as the counter increment —
was not implemented at all. Instead, the code stage built:

- A `ClickRecordingFilter` (a servlet filter) that watches for a successful redirect response *after the
  fact* and records the click via a separate component, `ClickRecorder`.
- `ClickRecorder.record(...)`, which catches any exception from the insert and only logs a warning —
  directly contradicting the approved trade-off ("if the event insert fails, the redirect fails").
- A real bug, present in both `ClickRecorder` and the generated `AnalyticsService`:
  `SELECT id FROM links WHERE code = ?` — the real column is `short_code`, not `code`. This would have
  failed at runtime on every single click or analytics request, even if the build had compiled.
- A standalone `AnalyticsProperties` class instead of the approved nested `AppProperties.Analytics` group
  — the same "ignore the nest-under-AppProperties instruction" pattern seen in the brownfield scenario.
- A `ClickEventSchemaInitializer` that runs its own `JdbcTemplate` DDL on startup, duplicating what the
  design explicitly said to do via `schema.sql`.

The tests stage, built as a separate model call against the same approved design, got the contract right:
`LinkServiceClickRecordingTest` calls `linkService.resolve(code, new ClickMetadata(...))` exactly as
approved. Because the code stage never created a `ClickMetadata` class, the build failed to compile. The
run safe-stopped and rolled back; the broken state is preserved at git tag `agent-failed/21bf5f2f`.

## Recovery
The generated output was reviewed file by file rather than discarded wholesale. Several files were
genuinely correct, self-contained, and reused as-is: `UserAgentClassifier`, `ReferrerHostExtractor`,
`ClickEvent`/`ClickEventRepository`, the DTO records (`DailyClicks`, `ReferrerCount`,
`LinkAnalyticsResponse`), and `AnalyticsController`. The flawed integration layer
(`ClickRecordingFilter`, `ClickRecorder`, `AnalyticsProperties`, `AnalyticsExceptionHandler`,
`ClickEventSchemaInitializer`) was discarded and replaced with the approved design's actual contract:

- A new `ClickMetadata` record in the `link` package.
- `LinkService.resolve(String, ClickMetadata)`: looks up the link, checks expiry, increments the
  counter, and saves the click event — all in one `@Transactional` method, matching the approved
  trade-off exactly. `resolve(String)` delegates to it with `ClickMetadata.none()` for backward
  compatibility.
- `RedirectController` now reads the optional `User-Agent`/`Referer` headers and passes them through.
- `AppProperties` gained a nested `Analytics` group (`defaultRangeDays`, `maxRangeDays`,
  `topReferrersLimit`), matching the existing `RateLimit`/`Link` convention.
- `AnalyticsService` was rewritten to use `LinkRepository.findByShortCode` (fixing the wrong-column bug)
  and the real `AppProperties.Analytics` group instead of the standalone properties class.
- `ApiExceptionHandler` gained one handler, `InvalidAnalyticsRangeException` → 400; `MethodArgumentType
  MismatchException` (malformed date/boolean query parameters) was already handled for free by the
  existing `ResponseEntityExceptionHandler` base class.
- `schema.sql` gained only the additive `CREATE TABLE IF NOT EXISTS click_event (...)` and its index,
  appended after the existing `links` table and link-expiry additions, all of which are untouched.

The tests stage's own suite was kept almost entirely intact: two more instances of the same
wrong-column-name bug were found and fixed in a shared test helper (`TestLinkSupport`) and one
test assertion (`$.clicks` → `$.clickCount`, the real stats field name), one test had an arithmetic bug in
its own string-length setup (fixed), two content-type assertions expected `application/json` instead of
the application's actual `application/problem+json` error responses (fixed), and three files named
`*IT.java` were renamed to `*Test.java` — the same no-Failsafe-plugin issue found during the greenfield
scenario's recovery.

The full change is one commit, `fix(human-review): add link analytics (manual recovery of broken AI
code-gen)` (`37c4a34`), with 68 new or fixed tests. `./mvnw -pl url-shortener verify` passes 162 of 162
tests; the whole-repo `./mvnw verify` passes 283 orchestrator tests plus those 162, with no regressions.

## What this demonstrates
- **Ambiguity handling, rejection and re-plan all worked as the scenario requires:** 10 ambiguities with
  stated assumptions on the first pass; a design rejected with specific, actionable feedback; an approved
  revision that closely matched the real codebase.
- **A `SUCCEEDED` stage status is not evidence of design conformance.** This is the distinct finding from
  this scenario, beyond what brownfield already showed. Brownfield's code was obviously broken (duplicate
  files, wrong package, corrupted schema). This run's code looked organized and built on its own merits —
  it just didn't implement what was actually approved, and introduced a real bug doing so. A review process
  that only checks "did it compile" or "are the new files in a sensible place" would still approve this.
  The only reliable check is comparing the diff against the approved design's stated contract, line by
  line, every time — which is exactly what caught this.
- **The CLI's per-invocation statelessness is an operational sharp edge**, now observed in two different
  forms across two scenarios (a dropped flag causing silent mock-mode fallback here; a missing quote
  truncating re-plan feedback in brownfield). Both were caught because the output was implausible on its
  face, not because the tooling flagged the mistake.

## Known gap
Same as the previous two scenarios: the orchestrator has no command to resume a `SAFE_STOPPED` run after
an out-of-band fix. Docs, the policy scan and the release-readiness summary for this scenario
(`artifacts/policy-scan.md`, `artifacts/release-readiness.md`; user-facing docs went into
`url-shortener/README.md` directly) were therefore written by hand instead of by the automated stages.
