# Project: agentic-sdlc-url-shortener

## Goal
Java 21 / Spring Boot 3.3 Maven multi-module repo:
1. `orchestrator/` - agentic SDLC orchestrator (primary focus).
2. `url-shortener/` - the product the orchestrator builds and modifies.

The orchestrator takes a natural-language change request and runs it through a stage graph:
requirements -> (impact analysis, brownfield only) -> design -> [code || tests] -> build+test (join)
-> [docs || policy scan] -> release readiness. Agents call Copilot models through an
`AgentRuntime` interface; `MockAgentRuntime` is used in tests.

## Orchestrator must provide
- Stage graph in `workflow.yml`, validated as a DAG at startup.
- Persisted state (WorkflowRun, StageExecution, Decision, Approval).
- Parallel stages with an explicit join.
- Entry/exit gates; human approval for requirements sign-off, schema/API changes, release.
- Bounded retries -> fallback model -> git rollback -> safe-stop.
- `PolicyGuard` rules: secret scan, schema-change approval, dependency allowlist,
  write-path restriction (agents may only write inside `url-shortener/`).
- Decision lineage with output hashes; downstream stages go STALE when an upstream hash changes.
- JSON-lines audit log and metrics: success rate, retries, rollbacks, MTTR, latency.
- One git branch per run (`agent/<scenario>-<run-id>`), one commit per stage.

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
