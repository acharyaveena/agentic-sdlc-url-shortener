# Ambiguous scenario

I gave the orchestrator one vague line, "Make analytics better for the URL shortener."

The scenario had to show ambiguity handling, a rejected design and a re-plan. All three happened.

## Summary

- The run was `21bf5f2f`.
- The requirements were approved the first time, with 10 ambiguities and 11 acceptance criteria.
- I rejected one design, and approved revision 3.
- The code and tests stages reported success, but the code ignored the approved design.
- I kept the correct generated parts and rewrote the integration by hand.
- The result was merged in PR #10, with 162 passing tests.
- The requirements, impact analysis and design files in `artifacts/` are unedited model output.

## Requirements

The stage did not guess. It listed 10 open questions, each with an assumption.

- What "better" means
- Which metrics to track
- Who uses the data
- Privacy of referrers and user agents
- Retention and level of detail
- Effect on redirect speed
- What happens to the existing click counter
- Bots and duplicate clicks
- Dashboard or API
- Clicks on expired links

It then set 11 testable acceptance criteria, and I approved them.

## Design, rejected and re-planned

| Revision | What happened |
|---|---|
| 1 | It ran on the mock AI by mistake (see below), and was replaced automatically. |
| 2 | It assumed JPA, Flyway, `/api/links` and wrong class names. I rejected it and listed the real facts. |
| 3 | It matched the real code and extended `LinkService.resolve` with click metadata. I approved it. |

- Revision 3 stated its trade-off clearly. Clicks are saved in the same transaction, so a failed insert fails the redirect.
- The mock run was my mistake. I ran `--approve` without `--orchestrator.agent.mode=copilot`.
- That command fell back to the mock AI, which returned placeholder text.
- I spotted it, and fixed it with a re-plan.

## Code and tests

Both stages reported `SUCCEEDED`. Nothing existing was damaged, and `pom.xml` was left alone. But the code did not follow the approved design.

- **A different design.** A servlet filter recorded clicks after the redirect, in a separate step.
- **Errors swallowed.** Failed inserts were only logged, the opposite of the approved trade-off.
- **A real bug.** Two files queried a `code` column. The real column is `short_code`.
- **Settings in the wrong place.** A new properties class was used instead of `AppProperties`.
- **Schema created in Java.** It should have gone in `schema.sql`.

The tests stage followed the design and called a `ClickMetadata` class. The code stage never created it, so the build failed.

## How I recovered

I reviewed each generated file and either kept it or replaced it.

| Kept as generated | Replaced or removed | Written by hand |
|---|---|---|
| User agent classifier | Click recording filter | `ClickMetadata` |
| Referrer host extractor | Click recorder | `LinkService.resolve` overload |
| `ClickEvent` and repository | Separate properties class | `AppProperties.Analytics` |
| Response records | Separate error handler | 400 mapping in `ApiExceptionHandler` |
| Analytics controller | Schema initializer | `schema.sql` additions |

I also rewrote `AnalyticsService` to use the real repository and settings.

The generated tests were good, so I kept most of them. I fixed these problems:

- The same wrong column name in a test helper
- A stats field name (`clickCount`, not `clicks`)
- Three content-type checks, since errors use `application/problem+json`
- One test with broken string math
- Three `*IT` files, renamed to `*Test` so they actually run

All 162 url-shortener tests passed.

## What I took from it

- Ambiguity handling, rejection and re-planning worked as required.
- A clean-looking change can still ignore an approved design. Only a check against the design caught it.
- CLI flags must be repeated on every command. Forgetting one is easy and silent.
- See [RISKS](../../docs/RISKS.md#1-an-approved-design-is-not-enforced).

## Known gap

- A safe-stopped run cannot resume after a human fix.
- So I wrote `policy-scan.md` and `release-readiness.md` by hand, in `artifacts/`.
- The user docs went into `url-shortener/README.md`.
