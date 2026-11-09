# Project: agentic-sdlc-url-shortener

## Goal
Java 21 / Spring Boot 3.3 Maven multi-module repo:
1. `orchestrator/` - agentic SDLC orchestrator (primary focus).
2. `url-shortener/` - the product the orchestrator builds and modifies.

The orchestrator takes a natural-language change request and runs it through a stage graph:
requirements -> (impact analysis, skipped for greenfield) -> design -> [code || tests] -> build+test (join)
-> [docs || policy scan] -> release readiness. Agents call Copilot models through an
`AgentRuntime` interface; `MockAgentRuntime` is used in tests.

## Scenarios the orchestrator must run
1. Greenfield: build the shortener from an empty module - create/redirect API, input validation,
   collision handling, click counting, rate limiting, OpenAPI spec, DB schema, unit + integration tests.
2. Brownfield: add link expiry to the existing shortener (impact analysis on modules, APIs, schema, data flow).
3. Ambiguous: "make analytics better" - requirements stage lists ambiguities and assumptions,
   a design is rejected at approval, and the run re-plans.
4. Test/docs improvement (optional if time is short): raise coverage and improve docs on the existing code.
5. Refactor/bug fix: described in docs only for now; becomes a run if time allows at the end.

## Stage artifacts
- requirements: normalized problem statement, ambiguity list, assumptions, acceptance criteria.
- impact analysis: affected modules, APIs, schema, data flows, risks.
- design: task list with dependencies and order, API (OpenAPI) and schema changes.
- code/tests/docs: files inside `url-shortener/` only.
- release readiness: risks, trade-offs, validation results, limitations.

## Orchestrator must provide
- Stage graph in `workflow.yml`, validated as a DAG at startup.
- Persisted state (WorkflowRun, StageExecution, Decision, Approval).
- Parallel stages with an explicit join.
- Entry/exit gates; human approval for requirements sign-off, schema/API changes, release.
- Bounded retries -> fallback model -> git rollback -> safe-stop.
- `PolicyGuard` rules: secret scan, schema-change approval, dependency allowlist,
  write-path restriction (agents may only write inside `url-shortener/`).
- Cross-stage context: each stage receives the approved artifacts of its upstream stages as input.
- Decision lineage with output hashes; downstream stages go STALE when an upstream hash changes.
- Re-plan keeps governance: re-run stages pass their gates and approvals again.
- Autonomy boundaries: agents may read the repo and write inside `url-shortener/` only; anything
  touching schema, public API, dependencies or release needs a human approval.
- JSON-lines audit log; every event has run id, stage, attempt, model, input/output hash, decision, actor.
- Metrics: success rate, retry and rollback frequency, MTTR, end-to-end and per-stage latency.
- One git branch per run (`agent/<scenario>-<run-id>`), one commit per stage.
- Humans own final quality: review the run branch, fix with `fix(human-review): ...`, then merge.

## Deliverables
- Runnable end-to-end prototype (one command per scenario, plus a mock mode that needs no Copilot login).
- `README.md`: overview and quick start.
- `docs/ARCHITECTURE.md`: components, orchestration model, control flow, key decisions.
- `docs/SETUP.md`: prerequisites, auth, build, run.
- `docs/TESTING.md`: test approach, coverage, how to run.
- `docs/RISKS.md`: risks, failure scenarios, trade-offs, guardrails, limitations, scaling path.
- `docs/SUMMARY.md`: plan and rationale, artifacts, validation results, assumptions, limitations.
- `scenarios/<name>/`: evidence per scenario - request, stage artifacts (requirements, task plan,
  impact analysis), approvals, audit log, metrics report, and a short walkthrough.

## What goes in the repo
- Only project code, docs and scenario evidence written in our own words.
- No secrets, tokens, personal data, local paths or tool/session logs; scan scenario evidence before committing.

## Engineering rules
- Packages by responsibility: workflow, engine, agent, gate, policy, recovery, replan, state, audit, metrics, api, cli.
- Program to interfaces (`AgentRuntime`, `PolicyGuard`, `RollbackStrategy`).
- Every public class gets JUnit 5 tests that assert real values.
- No secrets in code or git; auth comes from the signed-in Copilot CLI or `GH_TOKEN`.
- No new dependencies without calling them out and justifying them.
- Stay minimal: no Kafka, no external workflow engine, no Spring Security.

## Git
- Branches: `chore/`, `feat/`, `scenario/`, `docs/`; orchestrator-created runs use `agent/`.
- Conventional Commits: `type(scope): summary`. Manual edits after a run use `fix(human-review): ...`.

## Working style
- State the plan and files to touch before coding a section.
- Run `./mvnw verify` after changes and report failures honestly.
- Flag ambiguities and trade-offs instead of silently choosing.
