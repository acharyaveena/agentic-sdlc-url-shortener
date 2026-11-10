# Brownfield scenario

I asked the orchestrator to add link expiry to the existing shortener. Expired links should return `410 Gone` and eventually be cleaned up.

## Summary

- The run was `23df9175`.
- The requirements were approved the first time, with 14 acceptance criteria and 10 ambiguities.
- The design needed two re-plans and was approved on revision 3.
- The code and tests stages reported success, but their output was badly broken.
- I wrote the final code by hand from the approved design.
- The result was merged in PR #9, with 94 passing tests.
- The requirements, impact analysis and design files in `artifacts/` are unedited model output.

## Root cause

- Stages only receive earlier stages' text, never the real source files.
- For greenfield that is harmless, because nothing exists yet.
- For brownfield the agents had to guess what the code looks like.

## Requirements and impact analysis

- Requirements listed 10 ambiguities, such as the default TTL, cleanup timing and click counting on expired links.
- Each ambiguity had a stated assumption.
- Impact analysis flagged the schema change (`expires_at`) and the API change (`ttlSeconds`, `expiresAt`).
- Both needed approval, so I checked them before moving on.

## Design, three revisions

| Revision | What happened |
|---|---|
| 1 | It assumed JPA, Flyway and wrong class names. It said it had not seen the code. |
| 2 | Almost unchanged. I passed my feedback without quotes, so only the word "Corrections" reached the model. |
| 3 | I passed the feedback correctly, with real facts about the code. Much better, and I approved it. |

- Revision 3 listed the names it was unsure of and asked me to check them.
- I found two wrong guesses, the API path and the response class name.
- Three revisions is the limit, so no more re-plans were possible.

## Code and tests

Both stages reported `SUCCEEDED`. The output was not usable.

- **Wrong package.** Ten new files landed in the wrong package. Several duplicated real classes, including a clock bean that would clash with the real one.
- **`pom.xml` rewritten.** It dropped `spring-boot-starter-validation`.
- **`schema.sql` rewritten.** It renamed `long_url`, widened `short_code` and dropped both table constraints.
- **Design ignored.** Settings went into a new class instead of `AppProperties`. The tests stage got this right.

The build failed with more than 10 compile errors. The run rolled back and stopped. The failed state is kept as a local git tag, `agent-failed/23df9175`.

## How I recovered

Another live run would hit the same blind spot, so I wrote the feature by hand into the real files.

| File | Change |
|---|---|
| `AppProperties` | New `link` settings group for default TTL, cleanup interval and grace period |
| `Link` | New `expiresAt` field and `isExpired` check |
| `LinkRepository` | New query to delete expired links |
| `LinkService` | Sets expiry on create. Checks expiry before counting a click. |
| `LinkExpiredException`, `ExpiredLinkCleanupJob` | New. Return 410 and clean up on a schedule. |
| Request, response, controller, error handler | `ttlSeconds` in, `expiresAt` out, 410 mapping |
| `schema.sql` | Two lines added. The original table is untouched. |
| `pom.xml` | Not changed. No new dependency was needed. |

- I added 12 new tests.
- All 94 url-shortener tests passed, with no regressions.

## What I took from it

- The ambiguity handling and approval gates worked.
- Re-planning worked once I passed the feedback correctly.
- A `SUCCEEDED` stage is not proof that the output is usable.
- Files edited in place need the closest review, because damage there is silent.
- The real fix is to give brownfield stages the actual code. See [RISKS](../../docs/RISKS.md#2-agents-cannot-see-the-existing-code).

## Known gap

- A safe-stopped run cannot resume after a human fix.
- So I wrote `policy-scan.md` and `release-readiness.md` by hand, in `artifacts/`.
- The user docs went into `url-shortener/README.md`.
