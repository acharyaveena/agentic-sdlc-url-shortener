# Risks, failure scenarios, trade-offs, guardrails, and limitations

This document consolidates the findings from all three live scenario runs
(`scenarios/greenfield/`, `scenarios/brownfield/`, `scenarios/ambiguous-analytics/`), each of which
surfaced a real, reproducible failure mode — not a hypothetical one. They are ordered by how
consequential they were to discover.

## 1. An approved design does not self-enforce — a `SUCCEEDED` code/tests stage does not mean the code matches the design

**Found in:** ambiguous-analytics scenario.

The design stage produced an accurate, well-corrected design (Spring Data JDBC, the real schema approach,
the real API prefix, a specific integration contract: extend `LinkService.resolve` with a `ClickMetadata`
overload, recording clicks in the same transaction as the counter increment). It was reviewed and approved.
The code stage then built something else entirely — a separate, out-of-band `ClickRecordingFilter` that
silently swallowed failures (the opposite of the approved consistency trade-off) and queried a non-existent
database column, a bug that would have failed at runtime even if the build had compiled. Both the code and
tests stages reported `SUCCEEDED`. The tests stage (a separate model call against the same design) built
correctly against the approved contract, so the mismatch only surfaced as a compile failure in a third,
unrelated stage.

**Why this matters more than it looks:** a review process that checks "did the build pass" or "are the new
files in a sensible location" would not have caught this — the generated code was well-organized and
touched nothing existing. The only reliable check is diffing the actual code against the approved design's
stated contract, every time, for every stage that claims success.

**Guardrail that exists today:** none, automated. `PolicyGuard` checks file paths, dependencies, secrets,
and schema-change approval — it has no concept of "does this match the design." This is a real gap, not
an oversight to be fixed later with more prompting; it would need either a structural diff check against
the design's stated contract or, more realistically, continued mandatory human review before merge.

## 2. Brownfield code generation without real repository context is unsafe

**Found in:** brownfield scenario (most severe) and ambiguous-analytics (recurring in milder form).

`StageContext.upstreamOutputs` only ever carries the *text output* of previous stages — never the real
files in `url-shortener/`. For greenfield this is harmless (nothing exists to collide with). For a
brownfield change it means the design, code, and tests stages are reasoning from stale/absent information
about a codebase they cannot see:

- The brownfield design's first two revisions assumed JPA, Flyway, and wrong class/table names.
- The brownfield code stage went further: it created ten new files at the wrong, flat package, several of
  which **duplicated real classes** (including a `ClockConfig` that would have collided with a real Spring
  bean), and — most seriously — it **rewrote `pom.xml` and `schema.sql` in place** rather than editing them
  additively, silently dropping a real dependency (`spring-boot-starter-validation`) and **two database
  constraints** (`uk_links_short_code`, `ck_links_click_count_non_negative`).
- The ambiguous-analytics scenario's code stage, given the same kind of blind spot, independently guessed
  a wrong database column name (`code` instead of the real `short_code`) in two different files.

**Guardrails that worked:** `WritePathRule` confined every change to `url-shortener/`; `SchemaChangeRule`
required the schema change to already have a recorded approval; the build stage's real compile failure
caught the brownfield case outright (nothing silently shipped). None of these guardrails, however, would
have prevented the *silent regression* in `pom.xml`/`schema.sql` if the build had happened to still compile
— they check policy, not correctness of an in-place edit.

**Mitigation used in practice:** full manual recovery from the preserved `agent-failed/<run-id>` git tag,
reviewing every generated file against the real codebase file by file, discarding anything that duplicated
or corrupted existing files, and hand-implementing the approved design directly into the real files. This
is the single most time-consuming category of work across all three scenarios.

## 3. Hallucinated, non-compiling test assertions

**Found in:** greenfield scenario (attempt 2).

The tests stage wrote a chained MockMvc assertion (`.value(endsWith("/")).doesNotExist()`) that does not
compile — not a logic bug, a syntactically invalid API usage. This is an open-ended problem (any model can
hallucinate any API at any time); the project's judgment call was explicitly **not** to chase individual
hallucinations with prompt tweaks, since that does not generalize. The mitigation is structural: the build
stage's real compile step catches it, every time, before anything ships.

## 4. Cross-stage contract drift, in three different forms

**Found in:** all three scenarios, each time differently.

- Greenfield: the code stage wrote `FixedWindowRateLimiter(int, Duration, Clock)`; the tests stage, a
  separate model call against the same design, wrote `FixedWindowRateLimiter(AppProperties, Clock)` — the
  design's contracts table listed methods but not the exact constructor signature.
- Brownfield: the code stage ignored the approved design's explicit instruction to nest new config under
  `AppProperties` (matching the existing `RateLimit` convention) and built a standalone `LinkProperties`
  class instead; the tests stage, given the identical approved design, got this detail right.
- Ambiguous-analytics: the same "ignore the nest-under-`AppProperties` instruction" pattern recurred
  (`AnalyticsProperties` instead of `AppProperties.Analytics`), and separately, the code stage's actual
  click-recording *architecture* diverged from its own design (see finding 1).

**Root cause:** code and tests are two independent model calls against one shared design document. Nothing
forces them to agree on a detail the design under-specified, and nothing automatically detects when one of
them simply doesn't follow the design at all. A more detailed, example-driven design (full signatures, not
just a contracts table) reduced but did not eliminate this across the three runs.

## 5. Operator errors in driving the CLI, twice

**Found in:** brownfield (CLI quoting) and ambiguous-analytics (missing per-invocation flag).

- Brownfield: a re-plan's corrective feedback was passed as `--reason=$feedback` without quoting; Spring
  Boot's argument tokenizer split it on spaces and only the first word reached the model. The design
  regenerated almost unchanged.
- Ambiguous-analytics: an `--approve=...` call omitted `--orchestrator.agent.mode=copilot`. Because every
  CLI subcommand is a separate JVM reading `application.yml` fresh, the stages that ran as part of that
  call silently used the mock agent runtime, producing literal "(mock output)" placeholder text.

**Neither is an orchestrator defect** — both are human mistakes using the tool, confirmed by reading the
engine code, which correctly seeds and passes feedback/configuration when given it. They are listed here
because they are genuinely easy mistakes (both were caught only because the resulting output was
implausible on its face, not because the tool warned), and because the CLI's per-invocation statelessness —
a deliberate design choice so a run can pause for days — is precisely what makes them possible. See
`docs/SETUP.md` for the operational workaround (always repeat the mode flag; always quote multi-word
values).

## 6. No command to resume a `SAFE_STOPPED` run

**Found in:** all three scenarios.

Once a run safe-stops and rolls back, there is no `--resume` path that picks back up from an out-of-band
human fix — `ReplanService` explicitly only accepts `AWAITING_APPROVAL` or `FAILED` runs, not
`SAFE_STOPPED`. In practice this meant `docs`, `policy-scan`, and `release-readiness` for all three
scenarios were written by hand rather than by the automated stages, because by the time the human recovery
was done, the run itself had already terminated. This is a real limitation of the current prototype, not
worked around by any of the three scenarios — it is simply accepted and documented.

## Other known limitations (product-level, in `url-shortener`)

- Rate limiting is in-memory and per-instance; it does not hold across multiple instances or a restart.
- No authentication on any endpoint (explicitly out of scope per the project's engineering rules — no
  Spring Security).
- H2 is file-based by default and not intended for production use as-is.
- Click-event retention is unbounded in this prototype (a cleanup job exists for expired *links*, not for
  click events).
- Bot/user-agent classification is coarse and best-effort (substring matching on known tokens).
- No code-coverage gate; see `docs/TESTING.md`.

## Guardrails that worked as designed

- **Write-path restriction**: every scenario's generated files stayed inside `url-shortener/`.
- **Dependency allowlist with an explicit approval path**: the greenfield design's request for
  `io.swagger.parser.v3` and `org.jacoco` was reviewed and explicitly added to the allowlist rather than
  silently permitted or silently blocked.
- **Schema/API change approval gate**: every scenario's schema and API change was called out in the
  requirements document and required (and received) an explicit human approval before design was approved.
- **Bounded retry → fallback → rollback → safe-stop**: no run was ever left in an ambiguous or partially
  applied state; every failure resulted in either a successful recovery or a clean, tagged rollback.
- **Audit log and decision lineage**: every stage transition, approval, rejection, and re-plan for all three
  scenarios is reconstructable from `scenarios/<name>/audit.jsonl` and `lineage.md` — this document's
  findings were themselves verified against that record, not reconstructed from memory.
- **Evidence scanning before export**: `EvidenceScanner` ran on every export and found nothing to reject,
  confirming the write-path restriction held in practice, not just in policy.
