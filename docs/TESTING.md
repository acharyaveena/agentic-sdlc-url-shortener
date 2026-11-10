# Testing approach

## Summary

```
./mvnw verify
```

runs both modules: **283 orchestrator tests** and **162 `url-shortener` tests**, all green, no external
dependency (no AI provider login, no network). Both counts are enforced on every change in this project —
every scenario's manual recovery ended with a full `./mvnw verify` pass before being committed.

## Orchestrator (283 tests)

Entirely unit and component tests against `MockAgentRuntime` — no live model call is needed to validate the
orchestration logic itself. Representative coverage by package:

| Area | What is tested |
|---|---|
| `workflow` | DAG loading and validation: cycle detection, dangling `dependsOn`, `skipFor`. |
| `gate` | Exit-gate section checking; approval recording and blocking. |
| `policy` | Each `PolicyRule` independently: secret patterns, dependency allowlist, schema-change approval requirement, write-path restriction — including deliberately crafted violations that must be caught. |
| `recovery` | Retry-then-fallback sequencing, `GitRollbackStrategy` against a real temporary git repository (not mocked — `GitRollbackStrategyTest` actually shells out to `git`), `ChaosPlan` failure injection. |
| `replan` | Re-plan bumping revision and correctly marking only the actually-downstream stages `STALE`. |
| `state` | Entity persistence and a Hibernate schema-upgrade smoke test. |
| `workspace` | `GitWorkspace` against a real temporary repository; `FileWritingStageExecutor`/`FileChangeParser` against deliberately malformed agent output; `BuildStageExecutor`/`ProcessRunner` running a real (trivial) subprocess. |
| `audit`, `metrics` | Log line shape and hash redaction; success/retry/rollback rate and MTTR arithmetic against synthetic histories. |
| `OrchestratorApplicationTest` | End-to-end: a full mock-mode run through every stage to `release-readiness`, approval by approval. |

One test is skipped (`WorkspaceWriterTest`, platform-specific), not failing.

## `url-shortener` (162 tests)

Unit tests (Mockito, no Spring context) for pure logic: URL validation, short-code generation, the rate
limiter's fixed-window arithmetic, user-agent classification, referrer-host extraction, and `LinkService`'s
branching (collision retry, expiry check ordering, click recording) with a fixed `Clock`.

Integration tests (`@SpringBootTest` + `MockMvc`, real in-memory H2 — not mocked) for everything that
depends on the database, the web layer, or their interaction:

- Create/redirect/stats happy paths and every documented error status.
- `ConcurrentRedirectTest`: 50 concurrent requests against one link, asserting the click counter lands on
  exactly 50 — the real test for the atomic `UPDATE ... SET click_count = click_count + 1` claim.
- `RateLimitTest`/rate-limit-adjacent tests: real HTTP calls through the limiter with a controllable
  `MutableClock` so window boundaries are deterministic, not timing-dependent.
- Link expiry: TTL default and override, 410 before cleanup, 404 after cleanup, existing (never-expiring)
  links unaffected, cleanup job grace-period boundary — all against a fixed, advanceable clock.
- Click analytics: daily-series zero-filling, top-referrer ordering, bot filtering, date-range validation,
  a dedicated test that reads `INFORMATION_SCHEMA.COLUMNS` to assert no personal-data column ever exists,
  and a schema-idempotency test that re-runs `schema.sql` against a live database and expects no error.
- `ContractFilesTest`: parses `openapi.yaml` and `schema.sql` and asserts the endpoints, status codes, and
  table constraints actually documented/declared match what the code claims to implement — this is what
  catches an endpoint or constraint silently going missing from the spec or schema.

## Test infrastructure decisions

- **Fixed/advanceable clocks (`MutableClock`), not `Thread.sleep`,** for every time-dependent test (rate
  limiting, expiry, analytics date ranges). Deterministic and fast.
- **Real H2, not mocked repositories, for integration tests.** `Link`/`ClickEvent` are Spring Data JDBC
  records with hand-written `@Query` SQL; a mocked repository would not catch a wrong column name or a
  broken query, which is exactly the class of bug the AI-generated code introduced twice (see
  `docs/RISKS.md`).
- **Each integration test class hardcodes its own `spring.datasource.url`** (a fresh in-memory H2 schema
  per test class, with `DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE`) rather than sharing one
  global test database — this was the fix for a real bug found during the greenfield scenario (H2's
  default identifier case-folding didn't match Spring Data JDBC's quoted-lowercase generated queries).
- **`*Test.java`, never `*IT.java`.** No Failsafe plugin is configured, only Surefire, which silently never
  runs `*IT.java` classes. This was a real defect introduced by AI-generated test code in two of the three
  scenarios and is now an explicit house rule, checked by hand on every recovery.
- **No code-coverage gate (JaCoCo).** Test count and real execution (not a coverage percentage) are the
  evidence of thoroughness in this project; adding a coverage gate was discussed and deliberately deferred
  as out of scope for the assignment's timeframe.

## Limitations

- No load/performance testing beyond `ConcurrentRedirectTest`'s 50-request burst.
- No mutation testing.
- The three live orchestrator scenario runs are not themselves repeatable automated tests (they call a real
  LLM); their value is as recorded evidence under `scenarios/`, not as part of `./mvnw verify`.
