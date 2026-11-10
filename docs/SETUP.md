# Setup

## Prerequisites

- **JDK 21.** The Maven wrapper (`./mvnw`) is checked in, so no separate Maven install is needed — only a
  JDK. Set `JAVA_HOME` to it before running anything.
- **Git**, with the repository already cloned (the orchestrator shells out to `git` for branch/commit
  operations and to discover the repository root via `git rev-parse --show-toplevel`).
- **For live (non-mock) orchestrator runs only:** a GitHub Copilot CLI login (`copilot login`) and the
  `com.github:copilot-sdk-java` dependency's platform-specific runtime jar (the root `pom.xml` activates a
  `runtime-windows`/equivalent profile automatically based on your OS). No login or network access is
  needed for anything else — building, testing, and mock-mode orchestrator runs all work offline.

## Build everything

```bash
./mvnw verify
```

Builds both modules and runs the full test suite: 283 orchestrator tests, 162 `url-shortener` tests, no
external dependency required.

> **Windows PowerShell note:** `./mvnw ...` can report a non-zero exit code even when Maven printed
> `BUILD SUCCESS`, because of harmless stderr JVM warnings (native-access, deprecated API). Check the
> printed `BUILD SUCCESS`/`BUILD FAILURE` text, not the shell exit code.

## Run `url-shortener` standalone

```bash
./mvnw -pl url-shortener spring-boot:run
```

Listens on `:8080`, stores data in `url-shortener/data/urlshortener.mv.db` (H2 file mode). See
[`url-shortener/README.md`](../url-shortener/README.md) for the full API.

## Run the orchestrator

The CLI is one Spring Boot application; every subcommand is a separate process invocation (state persists
to a local file-based H2 database at `orchestrator/runs/orchestrator.mv.db`, so a paused run survives
between commands). Build the jar once, then invoke it from inside `orchestrator/` (the datasource path is
relative to the process's working directory, while the exported-evidence path is resolved against the git
repository root regardless of working directory):

```bash
cd orchestrator
../mvnw package -DskipTests
java -jar target/orchestrator-0.1.0-SNAPSHOT.jar <args>
```

### Mock mode (default, no login needed)

```bash
java -jar target/orchestrator-0.1.0-SNAPSHOT.jar \
  --scenario=demo --change-type=greenfield \
  --request="Build a URL shortener with create, redirect, and click counting."
```

Every stage returns deterministic placeholder text from `MockAgentRuntime` — useful for exercising the
workflow, gates, and recovery logic without any AI provider.

### Live mode (real GitHub Copilot models)

Append `--orchestrator.agent.mode=copilot` to **every single invocation for that run**, not just the first:

```bash
java -jar target/orchestrator-0.1.0-SNAPSHOT.jar \
  --scenario=demo --change-type=greenfield --request="..." \
  --orchestrator.agent.mode=copilot
```

> **This is the single most common operator mistake across all three scenario runs.** Each CLI invocation
> is a fresh JVM that reads `application.yml` from scratch; the property is *not* remembered between
> commands. Forgetting it on a later `--approve`/`--replan` call silently falls back to mock mode, visible
> only as literal "(mock output)" placeholder text in the next stage's markdown. There is no warning —
> always check the stage output before approving it.

Models per stage are configured in `orchestrator/src/main/resources/application.yml` under
`orchestrator.agent.models` (Opus for requirements/impact-analysis/design, Sonnet for code/tests/docs/
release-readiness by default); the fallback chain is under `orchestrator.recovery.fallback-models`.

### Driving a run

```text
--scenario=<name> [--change-type=greenfield|brownfield] [--request=<text>]   start a run
    [--chaos=<stage>:<count|always>[,...]]                                    inject failures to demo recovery
--approve=<run>:<stage> [--actor=<name>] [--reason=<text>]                    approve, then resume
--reject=<run>:<stage> --reason=<text> [--actor=<name>] [--replan]            reject; --replan redoes the stage
--replan=<run>:<stage> --reason=<text> [--actor=<name>]                       redo a stage; changed outputs make
                                                                               downstream stages STALE
--resume=<run>                                                                continue a paused run
--status=<run>                                                                show stages and approvals
--lineage=<run>                                                               show output hashes per revision
--metrics[=<run>]                                                             reliability metrics
--export=<run> --to=scenarios/<name>                                         copy scenario evidence (scanned first)
--stage=<id> --request=<text>                                                preview one stage
```

`--change-type` distinguishes greenfield (the `impact-analysis` stage is skipped — there is nothing to
analyze impact against) from brownfield (every stage runs). A deliberately ambiguous request should still
be submitted as `brownfield` if it targets the existing `url-shortener/` code, as in the
`ambiguous-analytics` scenario.

**Multi-word `--reason=`/`--request=` values on PowerShell** must be wrapped in explicit single quotes
inside the double-quoted argument, e.g. `--reason='$feedbackVariable'`, or Spring Boot's argument tokenizer
will silently truncate the value at the first space.

### Reviewing and exporting a run

Each stage's output is written to `orchestrator/runs/<run-id>/<stage>.md` for review before approving. Once
a run reaches a terminal state (`SAFE_STOPPED`, `FAILED`, or all the way to `release-readiness` approval),
export its evidence:

```bash
java -jar target/orchestrator-0.1.0-SNAPSHOT.jar --export=<run-id> --to=scenarios/<name>
```

This scans everything for secrets, local paths, and personal emails first and refuses to write if it finds
any.

## Known gap: resuming a `SAFE_STOPPED` run

There is currently no command to resume a `SAFE_STOPPED` run after an out-of-band human fix (see
`docs/RISKS.md`). All three scenarios needed `docs`/`policy-scan`/`release-readiness` written by hand for
this reason — the automated run had already rolled back by the time the human recovery was done.
