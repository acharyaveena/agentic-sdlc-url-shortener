# Brownfield scenario

The request was to add link expiry to the existing shortener. Expired links should return `410 Gone` and eventually be cleaned up.

## At a glance

| | |
|---|---|
| Run | `23df9175` |
| Requirements | Approved first time. 14 acceptance criteria, 10 ambiguities. |
| Design | Re-planned twice, approved on revision 3 |
| Code and tests | Reported success but were badly broken |
| Final code | Written by hand from the approved design |
| Merged in | PR #9 |
| Tests after the fix | 94 passing |

## Root cause

- Stages only receive earlier stages' text, never the real source files.
- For greenfield that is harmless, because nothing exists yet.
- For brownfield the agents had to guess what the code looks like.

## Requirements and impact analysis

- Requirements listed 10 ambiguities, such as default TTL, cleanup timing and click counting on expired links.
- Each ambiguity had a stated assumption.
- Impact analysis flagged the schema change (`expires_at`) and the API change (`ttlSeconds`, `expiresAt`).
- Both were marked as needing approval.

## Design, three revisions

| Revision | What happened |
|---|---|
| 1 | Assumed JPA, Flyway and wrong class names. The design said it had not seen the code. |
| 2 | Almost unchanged. The feedback was passed unquoted, so only the word "Corrections" reached the model. |
| 3 | Feedback passed correctly, with real facts about the code. Much better, and approved. |

- Revision 3 listed names it was unsure of and asked the reviewer to check them.
- The reviewer found two wrong guesses, the API path and the response class name.
- Three revisions is the limit, so no more re-plans were possible.

## Code and tests

Both stages reported `SUCCEEDED`. The output was not usable.

- **10 new files in the wrong package.** Several duplicated real classes, including a clock bean that would clash with the real one.
- **`pom.xml` was rewritten.** It dropped `spring-boot-starter-validation`.
- **`schema.sql` was rewritten.** It renamed `long_url`, widened `short_code` and dropped both table constraints.
- **The design was ignored.** Settings went into a new class instead of `AppProperties`. The tests stage got this right.

The build failed with more than 10 compile errors. The run rolled back and stopped. The failed state is kept as a local git tag, `agent-failed/23df9175`.

## Human recovery

Another live run would hit the same blind spot, so the feature was written by hand into the real files.

| File | Change |
|---|---|
| `AppProperties` | New `link` settings group: default TTL, cleanup interval, grace period |
| `Link` | New `expiresAt` field and `isExpired` check |
| `LinkRepository` | New query to delete expired links |
| `LinkService` | Sets expiry on create. Checks expiry before counting a click. |
| `LinkExpiredException`, `ExpiredLinkCleanupJob` | New. Return 410 and clean up on a schedule. |
| Request, response, controller, error handler | `ttlSeconds` in, `expiresAt` out, 410 mapping |
| `schema.sql` | Two lines added. The original table is untouched. |
| `pom.xml` | Not changed. No new dependency was needed. |

- 12 new tests were added.
- All 94 url-shortener tests passed, with no regressions.

## What this shows

- Ambiguity handling and approval gates worked.
- Re-planning worked once the feedback was passed correctly.
- A `SUCCEEDED` stage is not proof that the output is usable.
- Files edited in place need the closest review. Damage there is silent.
- The fix is architectural: give brownfield stages the real code. See [RISKS](../../docs/RISKS.md#2-agents-cannot-see-the-existing-code).

## Known gap

- A safe-stopped run cannot resume after a human fix.
- So `policy-scan` and `release-readiness` were written by hand, in `artifacts/`.
- The user docs went into `url-shortener/README.md`.
