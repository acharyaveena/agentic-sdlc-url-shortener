# Risks, trade-offs and limitations

Each finding below came from a live run, not a guess. The runs are in `scenarios/`.

## Findings at a glance

| # | Finding | Seen in | Guardrail today |
|---|---|---|---|
| 1 | An approved design is not enforced | Ambiguous | Human review |
| 2 | Agents cannot see the existing code | Brownfield, ambiguous | Write-path rule, schema approval, real build |
| 3 | Hallucinated APIs | Greenfield | Real build |
| 4 | Code and tests disagree | All three | Real build, detailed designs |
| 5 | Easy CLI mistakes | Brownfield, ambiguous | Warnings in SETUP |
| 6 | A safe-stopped run cannot resume | All three | Artifacts written by hand |

## 1. An approved design is not enforced

- The approved design said to record clicks inside `LinkService`, in the same transaction as the counter.
- The code stage built a separate filter instead.
- That filter ignored insert errors and queried a column that does not exist.
- The tests stage followed the design, so the build failed.
- The generated code looked clean. Only a line-by-line check against the design caught it.

**Next step.** An automated check that code matches the design's stated contracts.

## 2. Agents cannot see the existing code

- Stages only receive earlier stages' text, never the real source files.
- Designs guessed the wrong framework (JPA), migration tool (Flyway) and class names.
- Brownfield code created 10 files in the wrong package, several duplicating real classes.
- It rewrote `pom.xml` and dropped a dependency.
- It rewrote `schema.sql`, renamed a column and dropped two constraints.
- Silent damage to existing files is worse than a build failure. The real build caught this case, but it would miss damage that still compiles.

**Next step.** Give brownfield stages the real file contents.

## 3. Hallucinated APIs

- In greenfield run 2, a test chained two assertions in a way that does not compile.
- The real build catches this every time.
- Prompts were not tuned for it, because a fix for one hallucination does not generalize.

## 4. Code and tests disagree

- In greenfield, code and tests used different constructors for the rate limiter.
- In brownfield and ambiguous, code put new settings in a separate class instead of `AppProperties`, as the design said.
- Code and tests are two separate AI calls, and nothing forces them to agree.
- Designs now list exact signatures. This reduced the problem but did not remove it.

## 5. Easy CLI mistakes

- An unquoted `--reason` was cut at the first space, so the model got one word of feedback.
- A command without `--orchestrator.agent.mode=copilot` silently used the mock AI.
- Each command is a new process, so nothing carries over between commands.
- `docs/SETUP.md` warns about both.

**Next step.** Store the agent mode with the run.

## 6. A safe-stopped run cannot resume

- After a human fixed the code, the run could not continue to docs, policy scan and release.
- Those three artifacts were written by hand for each scenario.

**Next step.** A resume command that re-checks gates after a human fix.

## Trade-offs

| Choice | Benefit | Cost |
|---|---|---|
| Record each click in the redirect transaction | Counter and events always match | A failed insert fails the redirect |
| Group analytics in Java, not SQL | Portable and simple | Memory grows with range, so ranges are capped at 366 days |
| Test the engine with a mock AI | Fast, free, repeatable tests | Real model behavior is only seen in live runs |
| One process per CLI command | Runs can wait days, and each action is auditable | Flags must be repeated every time |
| Human recovery instead of more live runs | Faster and cheaper with full context | The automated stages after the fix did not run |

## Guardrails that worked

- All generated files stayed inside `url-shortener/`.
- New dependencies needed approval. Two were reviewed and added to the allowlist.
- Every schema and API change was approved before design moved on.
- Every failure ended in a clean rollback with the failed state tagged.
- The audit log and lineage let every finding here be checked against the record.
- The evidence scan found no secrets or personal data in any export.

## Product limitations

- The rate limiter is in memory, per instance.
- There is no authentication.
- H2 is not meant for production.
- Click events are kept forever. Only expired links are cleaned up.
- Bot detection uses simple keyword matching.
- Self-link checks match exact hosts, without DNS lookups.
