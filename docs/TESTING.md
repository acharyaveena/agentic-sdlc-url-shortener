# Testing

## How to run

```bash
./mvnw verify
```

| Module | Tests | Needs |
|---|---|---|
| orchestrator | 283 (1 skipped) | Nothing. Uses the mock AI. |
| url-shortener | 162 | Nothing. Uses in-memory H2. |

- The skipped test needs symbolic links, which some Windows setups do not allow.
- CI runs the same command on every pull request.

## Orchestrator tests

| Area | What is tested |
|---|---|
| workflow | Loading the graph, cycle detection, missing dependencies, skipped stages |
| gate | Required headings and approval blocking |
| policy | Each rule, including deliberate violations that must be caught |
| recovery | Retry then fallback order, rollback against a real temporary git repo, injected failures |
| replan | Revision numbers and which stages go stale |
| state | Saving entities and upgrading the schema |
| workspace | Git operations, parsing bad agent output, running a real subprocess |
| audit and metrics | Log format, hashing, and the metric math |
| full app | A complete mock run to release, a re-plan, a fallback recovery and a rollback |

## URL shortener tests

**Unit tests** (no Spring, fixed clock)

- URL validation
- Short code generation
- Rate limiter window math
- User agent and referrer parsing
- `LinkService` logic for collisions, expiry and click recording

**Integration tests** (Spring Boot, MockMvc, real in-memory H2)

- Every endpoint and every documented error status
- 50 concurrent redirects land on exactly 50 clicks
- Rate limit windows, using a clock the test controls
- Expiry rules: default TTL, 410 before cleanup, cleanup grace period
- Analytics: daily totals, top referrers, bot filter, date range checks
- No column can store an IP address, full user agent or full referrer
- `schema.sql` can run twice without errors
- `openapi.yaml` and `schema.sql` match what the code implements

## Test rules

- **Control time with a test clock.** No `sleep` calls, so time-based tests are fast and stable.
- **Use a real database.** Mocks would miss a wrong column name, which the AI-generated code did twice.
- **Give each test class its own database.** This prevents tests from affecting each other.
- **Name test classes `*Test`.** Only Surefire is configured, so `*IT` classes would silently never run. Generated code made this mistake twice.

## Limitations

- No coverage gate. Test count and real execution are the evidence.
- No load testing beyond the 50-request concurrency test.
- No mutation testing.
- The live scenario runs call a real AI, so they are evidence in `scenarios/`, not repeatable tests.
