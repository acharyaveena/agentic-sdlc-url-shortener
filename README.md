# Agentic SDLC orchestrator and URL shortener

An orchestrator that turns a plain-English change request into reviewed, tested code. It runs AI agents through a governed software lifecycle. The product it builds is a URL shortener.

## What's in the repo

| Folder | What it is |
|---|---|
| `orchestrator/` | The workflow engine. Runs agents through requirements, design, code, tests, build, docs and release, with human approvals. |
| `url-shortener/` | The product. A Spring Boot service with short links, redirects, stats, expiry and analytics. |
| `scenarios/` | Evidence from three live runs against real Copilot models. |
| `docs/` | Architecture, setup, testing, risks and summary. |

## How the two parts fit together

- You give the orchestrator a request, such as "add link expiry".
- It plans the change and writes code into `url-shortener/`.
- It builds and tests that code, and asks a human to approve key steps.
- The `url-shortener/` code in this repo came from three live runs. A human reviewed and fixed it before merge.
- The quick demo uses a mock AI. It shows the full workflow but writes only a small placeholder class.

## Quick start

You need JDK 21 or newer and Git. No AI login is needed.

**Windows PowerShell**

```powershell
$env:JAVA_HOME = "C:\path\to\your\jdk"
./mvnw verify
```

**macOS or Linux**

```bash
export JAVA_HOME=/path/to/your/jdk
./mvnw verify
```

You should see `BUILD SUCCESS`, with 283 orchestrator tests and 162 url-shortener tests.

Then try it out:

- [Run the orchestrator demo](docs/SETUP.md#run-the-orchestrator-demo)
- [Run the URL shortener](docs/SETUP.md#run-the-url-shortener)

## The three scenarios

| Scenario | Request | What happened |
|---|---|---|
| [Greenfield](scenarios/greenfield/walkthrough.md) | Build the shortener from scratch | Three runs, each stopped safely on a real build failure. A human fixed the third. |
| [Brownfield](scenarios/brownfield/walkthrough.md) | Add link expiry | The design was re-planned twice. Generated code damaged existing files, so a human rebuilt the feature. |
| [Ambiguous](scenarios/ambiguous-analytics/walkthrough.md) | "Make analytics better" | Ten ambiguities listed. The design was rejected and re-planned. The code then ignored the approved design. |

## Documentation

| Doc | Covers |
|---|---|
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Components, stage graph, control flow and key decisions |
| [SETUP](docs/SETUP.md) | Build, run and CLI commands |
| [TESTING](docs/TESTING.md) | Test approach and coverage |
| [RISKS](docs/RISKS.md) | Findings, trade-offs, guardrails and limitations |
| [SUMMARY](docs/SUMMARY.md) | Plan, results, assumptions and limitations |

## Ground rules

- Agents may only write inside `url-shortener/`.
- Schema, API, dependency and release changes need human approval.
- Each run gets its own git branch, with one commit per stage.
- Failed runs are tagged and kept, not deleted.
- A human reviews every change before it is merged.
