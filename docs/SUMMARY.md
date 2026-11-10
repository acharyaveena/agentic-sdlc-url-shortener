# Summary

## Plan

1. Build the orchestrator first and test it against a mock AI.
2. Run three live scenarios with real Copilot models.
3. Review every generated change and fix it by hand where needed.
4. Record what went wrong and why.

The orchestration is the main deliverable, so it was built and tested before any live AI runs. That way, problems found later point at AI output, not at the engine.

## What was built

| Artifact | Where | Contents |
|---|---|---|
| Orchestrator | `orchestrator/` | Stage graph, engine, gates, policy rules, recovery, re-planning, audit, metrics, CLI. 283 tests. |
| URL shortener | `url-shortener/` | Links, redirects, stats, validation, rate limiting, expiry, analytics, OpenAPI spec, SQL schema. 162 tests. |
| Scenario evidence | `scenarios/` | Request, stage outputs, approvals, lineage, metrics, audit log and a walkthrough for each run |
| Docs | `README.md`, `docs/` | Architecture, setup, testing, risks and this summary |

## Validation

- `./mvnw verify` passes for the whole repo.
- No generated code was merged without human review.
- Every schema, API and dependency change has a recorded approval.
- Every failure is documented in its scenario walkthrough.

## Key findings

| # | Finding | Details |
|---|---|---|
| 1 | An approved design is not enforced | [RISKS](RISKS.md#1-an-approved-design-is-not-enforced) |
| 2 | Agents cannot see the existing code | [RISKS](RISKS.md#2-agents-cannot-see-the-existing-code) |
| 3 | Hallucinated APIs | [RISKS](RISKS.md#3-hallucinated-apis) |
| 4 | Code and tests disagree | [RISKS](RISKS.md#4-code-and-tests-disagree) |
| 5 | Easy CLI mistakes | [RISKS](RISKS.md#5-easy-cli-mistakes) |
| 6 | A safe-stopped run cannot resume | [RISKS](RISKS.md#6-a-safe-stopped-run-cannot-resume) |

## Assumptions

- Agents may read the repo and write only in `url-shortener/`.
- Schema, API, dependency and release changes always need a human.
- Mock tests are enough to prove the engine works. Live runs were needed only for the scenarios.
- A human with full failure context fixes problems faster than repeated AI runs.

## Limitations

- Brownfield stages cannot see the real code.
- Nothing checks generated code against the approved design.
- A safe-stopped run cannot resume.
- No coverage gate, load testing or mutation testing.
- Product limits are listed in [RISKS](RISKS.md#product-limitations).
