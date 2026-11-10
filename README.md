# agentic-sdlc-url-shortener

A governed agentic SDLC orchestrator, demonstrated by having it build and evolve a URL shortener.

Two Maven modules:

- **`orchestrator/`** — the agentic orchestrator. Takes a natural-language change request and runs it
  through a staged workflow (requirements → impact analysis → design → code/tests → build+test →
  docs/policy-scan → release readiness), calling an LLM for each stage, enforcing human approval gates,
  policy guardrails, bounded retries with fallback and rollback, and producing an audit trail and
  reliability metrics.
- **`url-shortener/`** — the product the orchestrator builds and modifies: a Spring Boot + Spring Data JDBC
  URL shortener with create/redirect/stats APIs, collision handling, rate limiting, link expiry, and
  per-link click analytics.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the orchestrator works,
[`docs/SETUP.md`](docs/SETUP.md) to build and run it, [`docs/TESTING.md`](docs/TESTING.md) for the test
approach, [`docs/RISKS.md`](docs/RISKS.md) for known risks and limitations, and
[`docs/SUMMARY.md`](docs/SUMMARY.md) for the final engineering summary.

## Quick start

```bash
# Prerequisites: JDK 21, the bundled Maven wrapper (no local Maven install needed)
./mvnw verify
```

That builds and tests both modules: 283 orchestrator tests and 162 url-shortener tests, all without any
external dependency — the orchestrator defaults to a mock agent runtime that needs no AI provider login.

To run the real `url-shortener` service standalone:

```bash
./mvnw -pl url-shortener spring-boot:run
# listens on :8080; see url-shortener/README.md for the API
```

To run the orchestrator against a real request (mock mode, no login required):

```bash
cd orchestrator
../mvnw package -DskipTests
java -jar target/orchestrator-0.1.0-SNAPSHOT.jar \
  --scenario=demo --change-type=greenfield \
  --request="Build a URL shortener with create, redirect, and click counting."
```

This prints the run id and pauses at the first approval gate. Full CLI usage, how to switch to a real
Copilot-backed run, and how to drive a run through approval/rejection/re-plan/export are in
[`docs/SETUP.md`](docs/SETUP.md).

## Scenario evidence

Three full scenarios were run end-to-end through the live orchestrator (against the real GitHub Copilot
SDK, not the mock runtime) and are preserved as evidence under [`scenarios/`](scenarios/):

| Scenario | Evidence | What it demonstrates |
|---|---|---|
| Greenfield | [`scenarios/greenfield/`](scenarios/greenfield/) | Building the shortener from an empty module. Needed three live runs (two safe-stopped on real build failures) before a human-reviewed recovery produced a 82-test-green build. |
| Brownfield | [`scenarios/brownfield/`](scenarios/brownfield/) | Adding link expiry to the existing shortener. Impact analysis and a twice-re-planned design were accurate, but the code/tests stages produced structurally broken output (wrong package, corrupted `pom.xml`/`schema.sql`) that required a full manual recovery. |
| Ambiguous | [`scenarios/ambiguous-analytics/`](scenarios/ambiguous-analytics/) | "Make analytics better" — a deliberately vague request. Requirements listed 10 ambiguities with stated assumptions; the first design was rejected and re-planned; the code stage then silently deviated from its own *approved* design while still reporting success. |

Each scenario's `walkthrough.md` is the best single entry point — it narrates the run end to end, including
what went wrong and why. The cumulative risk findings across all three are consolidated in
[`docs/RISKS.md`](docs/RISKS.md).

## Repository layout

```
orchestrator/        agentic SDLC orchestrator (primary focus)
  src/main/java/.../workflow      stage graph definition and DAG validation
  src/main/java/.../engine        the engine that runs stages against the graph
  src/main/java/.../agent         AgentRuntime abstraction (mock + real Copilot SDK)
  src/main/java/.../gate          approval gates
  src/main/java/.../policy        PolicyGuard rules (secrets, dependencies, schema, write-path)
  src/main/java/.../recovery      bounded retries, fallback model, git rollback, safe-stop
  src/main/java/.../replan        re-planning a stage, staleness propagation
  src/main/java/.../state         persisted run/stage/approval/decision entities (H2)
  src/main/java/.../audit         JSON-lines audit log
  src/main/java/.../metrics       reliability metrics
  src/main/java/.../workspace     git branch/commit management, build/policy-scan execution
  src/main/java/.../cli           command-line entry point
  src/main/resources/workflow.yml the stage graph
  src/main/resources/prompts/     per-stage system prompts
url-shortener/        the product the orchestrator builds and modifies
scenarios/            evidence from the three live scenario runs
docs/                 architecture, setup, testing, risks, summary
```

## Working rules this project followed

- Agents may only write inside `url-shortener/`; anything touching schema, public API, dependencies or
  release requires a human approval, enforced by `PolicyGuard`.
- One git branch per orchestrator run (`agent/<scenario>-<run-id>`), one commit per stage, failed runs
  preserved at an `agent-failed/<run-id>` tag rather than discarded.
- Humans own final quality: every scenario's generated code was reviewed, and where it was broken, fixed
  with a `fix(human-review): ...` commit under a real identity — never silently accepted.
