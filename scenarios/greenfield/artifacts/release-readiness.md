## Summary
A URL shortener service was built for the `url-shortener` module from a single natural-language request.
Three live orchestrator runs were needed: the first two safe-stopped on real build failures (a missing
approved dependency and a hallucinated test assertion), and the third produced a working design and most of
the implementation but failed its automated build on a code/tests contract mismatch. That third attempt's
code and tests were recovered from the orchestrator's preserved failed-state tag and fixed by a human
reviewer. `./mvnw -pl url-shortener verify` now passes with 82 tests, including real integration and
concurrency tests against H2.

## Validation results
- **Requirements and design:** reviewed and approved by a human at each checkpoint (see `approvals.md`).
- **Build and tests:** `./mvnw -pl url-shortener verify` — 82 of 82 tests pass, run twice with no flakiness.
  This includes `ConcurrentRedirectTest` (50 concurrent redirects, exact click count) and `RateLimitTest`
  (real HTTP calls through MockMvc against the token/window limiter).
- **Policy scan:** performed manually after the automated run stopped early; see `artifacts/policy-scan.md`.
  No secrets, no disallowed dependencies.
- **Contract:** `src/main/resources/openapi.yaml` describes the three endpoints and their status codes;
  `ContractFilesTest` checks the schema and OpenAPI files exist and parse.

## Risks and trade-offs
- **Rate limiting is in-memory and per-instance.** It does not hold across multiple instances or a restart.
  Accepted for this scope; documented in `url-shortener/README.md`.
- **Self-reference checking is exact-match only**, with no DNS resolution. A hostname that resolves to a
  blocked IP is not caught. Accepted as out of scope.
- **No authentication on any endpoint.** Anyone can create links or read any link's stats. Out of scope for
  this request; would need to be addressed before a public deployment.
- **Generated code is not automatically trustworthy.** All three live runs produced different real defects
  before anything passed: an unapproved dependency request, a hallucinated test assertion, and a genuine
  cross-stage contract mismatch between the code and tests stages (each stage is a separate model call
  against the same design, so they can disagree on details the design didn't pin down, such as a
  constructor's exact parameter list). A fourth defect, found only after fixing the first two, was a real
  H2 identifier case-folding bug that neither stage would have caught without the integration tests
  actually running. This is why human review before merge is a mandatory step, not an optional one.
- **The orchestrator has no way to resume a safe-stopped run after an out-of-band fix.** Docs, the policy
  scan and this summary were completed by hand rather than by the automated `docs`/`policy-scan`/
  `release-readiness` stages, because the run that reached `code`/`tests` had already safe-stopped. This is
  recorded as a limitation in `docs/RISKS.md`.

## Limitations
- The automated pipeline did not itself produce a passing build for this scenario; a human did, following
  the project's own rule that humans own final quality. This is real, structural evidence of controlled
  autonomy working as designed, not a shortfall hidden from the record — see `../greenfield-first-attempt/`,
  `../greenfield-second-attempt/` and `../greenfield-third-attempt/` for the three safe-stopped attempts.
- Coverage was not measured (no JaCoCo gate in this design); test count and real execution are the evidence
  of thoroughness here.

## Recommendation
RELEASE WITH CONDITIONS: merge once a reviewer has read `fix(human-review)` commit `438f6f2` and confirmed
the two fixes (`FixedWindowRateLimiter` overload, H2 case-folding) are sound. No further automated gate
blocks it; `./mvnw verify` passes for the whole repository.
