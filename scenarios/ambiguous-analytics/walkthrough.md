# Ambiguous scenario

The request was one vague line: "Make analytics better for the URL shortener."

This scenario had to show ambiguity handling, a rejected design and a re-plan. All three happened.

## At a glance

| | |
|---|---|
| Run | `21bf5f2f` |
| Requirements | Approved first time. 10 ambiguities, 11 acceptance criteria. |
| Design | Rejected once, approved on revision 3 |
| Code and tests | Reported success. The code ignored the approved design. |
| Final code | Generated parts kept where correct, integration rewritten by hand |
| Merged in | PR #10 |
| Tests after the fix | 162 passing |

## Requirements

The stage did not guess. It listed 10 open questions, each with an assumption.

- What "better" means
- Which metrics to track
- Who uses the data
- Privacy of referrers and user agents
- Retention and detail
- Effect on redirect speed
- What happens to the existing click counter
- Bots and duplicate clicks
- Dashboard or API
- Clicks on expired links

It then set 11 testable acceptance criteria and was approved first time.

## Design, rejected and re-planned

| Revision | What happened |
|---|---|
| 1 | Ran on the mock AI by mistake (see below). Replaced automatically. |
| 2 | Assumed JPA, Flyway, `/api/links` and wrong class names. **Rejected** with the real facts. |
| 3 | Matched the real code. Extended `LinkService.resolve` with click metadata. **Approved.** |

- Revision 3 stated a clear trade-off. Clicks are saved in the same transaction, so a failed insert fails the redirect.
- The operator mistake was an `--approve` command run without `--orchestrator.agent.mode=copilot`.
- That command fell back to the mock AI, which returned placeholder text.
- It was caught and fixed with a re-plan.

## Code and tests

Both stages reported `SUCCEEDED`. Nothing existing was damaged, and `pom.xml` was left alone. But the code did not follow the approved design.

- **A different design.** A servlet filter recorded clicks after the redirect, in a separate step.
- **Errors swallowed.** Failed inserts were only logged, the opposite of the approved trade-off.
- **A real bug.** Two files queried a `code` column. The real column is `short_code`.
- **Settings in the wrong place.** A new properties class instead of `AppProperties`.
- **Schema created in Java** instead of in `schema.sql`.

The tests stage followed the design and called a `ClickMetadata` class. The code stage never created it, so the build failed.

## Human recovery

Each generated file was reviewed and either kept or replaced.

| Kept as generated | Replaced or removed | Written by hand |
|---|---|---|
| User agent classifier | Click recording filter | `ClickMetadata` |
| Referrer host extractor | Click recorder | `LinkService.resolve` overload |
| `ClickEvent` and repository | Separate properties class | `AppProperties.Analytics` |
| Response records | Separate error handler | 400 mapping in `ApiExceptionHandler` |
| Analytics controller | Schema initializer | `schema.sql` additions |

`AnalyticsService` was rewritten to use the real repository and settings.

The generated tests were good and mostly kept. Fixes were needed for:

- The same wrong column name in a test helper
- A stats field name (`clickCount`, not `clicks`)
- Three content-type checks (errors use `application/problem+json`)
- One test with broken string math
- Three `*IT` files renamed to `*Test`, so they actually run

All 162 url-shortener tests passed.

## What this shows

- Ambiguity handling, rejection and re-planning worked as required.
- A clean-looking change can still ignore an approved design. Only a check against the design caught it.
- CLI flags must be repeated on every command. Forgetting one is easy and silent.
- See [RISKS](../../docs/RISKS.md#1-an-approved-design-is-not-enforced).

## Known gap

- A safe-stopped run cannot resume after a human fix.
- So `policy-scan` and `release-readiness` were written by hand, in `artifacts/`.
- The user docs went into `url-shortener/README.md`.
