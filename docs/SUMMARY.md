# Final engineering summary

## Plan and rationale

The assignment's critical differentiator is the orchestration layer, not the URL shortener itself — so the
plan was to build a genuinely governed, stateful, multi-stage orchestrator first (sections 0–5: workflow
engine, agent runtime abstraction, gates and policy guardrails, recovery controls, observability and
re-plan), validated by 283 of its own tests against a mock agent runtime, before spending any live-model
budget. Only once that engine was solid did the three scenarios run against the real GitHub Copilot SDK,
specifically so that any defects found would be defects in how AI-generated engineering output behaves
under governance, not defects in the governance mechanism itself.

The three required scenarios were chosen to stress different parts of that mechanism on purpose:
greenfield (does the full stage graph work end to end with nothing to collide with), brownfield (does the
orchestrator handle modifying real, existing code safely), and ambiguous (does requirements-stage ambiguity
handling and the reject/re-plan approval path work as designed). All three were run live, not simulated,
and every defect found was investigated to a root cause rather than patched over — see `docs/RISKS.md`.

## Artifacts produced

- **Orchestrator** (`orchestrator/`): stage graph (`workflow.yml`) and loader/validator; `WorkflowEngine`;
  `AgentRuntime` abstraction with a mock and a real Copilot SDK implementation; `PolicyGuard` with four
  rules; `RetryingStageRunner` + `GitRollbackStrategy` + `ChaosPlan`; `ReplanService` with staleness
  propagation; JPA-persisted run/stage/approval/decision state; JSON-lines audit log; reliability metrics;
  evidence exporter with a pre-write secret/PII scanner; a CLI covering every operation above. 283 tests.
- **`url-shortener`**: create/redirect/stats APIs with URL validation, collision handling, and rate
  limiting (greenfield); link expiry with a scheduled cleanup job (brownfield); per-link click analytics
  with privacy-reduced (host-only, category-only) event recording (ambiguous). An OpenAPI spec and a plain
  SQL schema, both additively maintained across all three scenarios. 162 tests.
- **Three full scenario evidence sets** (`scenarios/greenfield/`, `scenarios/brownfield/`,
  `scenarios/ambiguous-analytics/`): request, every stage's actual output across every revision, every
  approval/rejection/re-plan decision with its reason, decision lineage with hashes, reliability metrics,
  a full JSON-lines audit log, and a hand-written walkthrough tying the whole run together — including what
  went wrong and exactly how it was fixed.
- **This documentation set** (`README.md`, `docs/ARCHITECTURE.md`, `docs/SETUP.md`, `docs/TESTING.md`,
  `docs/RISKS.md`, `docs/SUMMARY.md`).

## Validation results

- Whole-repository `./mvnw verify` passes: 283 orchestrator tests + 162 `url-shortener` tests, zero
  regressions carried across all three scenarios' recoveries.
- All three scenarios' generated output was reviewed against the real codebase and, where broken, fixed and
  re-verified before being committed — never merged as generated.
- Every schema change, API change, and dependency addition across all three scenarios has a recorded human
  approval traceable in `scenarios/<name>/approvals.md`.
- Every failure (two greenfield build failures, one brownfield build failure, one ambiguous-analytics build
  failure) is preserved at an `agent-failed/<run-id>` git tag and documented in its scenario's walkthrough —
  nothing was hidden or silently retried away.

## Risks, trade-offs, and limitations

Consolidated in full in `docs/RISKS.md`. The headline findings, in order of how much they changed how this
project should be read:

1. An **approved design does not self-enforce** — a stage reporting `SUCCEEDED` is not evidence that its
   output matches what was approved (ambiguous-analytics).
2. **Brownfield code generation without real repository context is unsafe** — the design, code, and tests
   stages cannot see the actual files they are meant to modify, which produced both wrong assumptions
   (recoverable via re-plan) and, once, outright file corruption in files edited in place (brownfield).
3. Hallucinated, non-compiling output is a real but open-ended risk that structural checks (the real
   build stage) catch, rather than something worth chasing with prompt tweaks (greenfield).
4. Cross-stage contract drift between independently-generated code and tests recurred in every scenario in
   a different form.
5. The CLI's per-invocation statelessness (a deliberate choice, so a run can pause for days awaiting
   approval) makes two specific operator mistakes easy: forgetting to quote a multi-word `--reason=`/
   `--request=` value, and forgetting to repeat `--orchestrator.agent.mode=copilot` on every single command
   for a run.
6. There is no command to resume a `SAFE_STOPPED` run after an out-of-band human fix, which is why every
   scenario's `docs`/`policy-scan`/`release-readiness` artifacts were written by hand.

## Assumptions

- "Controlled autonomy" means agents may freely read the repository and write inside `url-shortener/`, but
  anything touching schema, public API, dependencies, or release requires a human decision — this was
  treated as a hard boundary, enforced by `PolicyGuard`, not a guideline.
- Mock-mode testing (283 orchestrator tests, zero live-model calls) is sufficient to validate the
  orchestration mechanism itself; only the three required scenarios needed to run against a real model.
- A human reviewer with full visibility into *why* something failed (the preserved `agent-failed` tag, the
  audit log, the lineage) can produce a correct fix faster and more reliably than spending additional live
  model budget retrying the same request — this assumption was tested against directly in every scenario's
  recovery and held in practice.
- "Each AI-generated output is reviewed and, if necessary, fixed by a human before being merged" is a
  requirement of this project, not an optional step — every `fix(human-review): ...` commit exists because
  of this, and none of the three scenarios' generated code was merged unreviewed.

## Limitations

- No mechanism exists today to give the design/code/tests stages the real repository's file contents for a
  brownfield change — this is the most consequential limitation found, and the most actionable scaling
  path: it would likely eliminate most of findings 2 and 4 above.
- No mechanism exists to automatically diff a stage's output against the design it was supposed to
  implement — this is what would be needed to catch finding 1 automatically rather than only on manual
  review.
- No resume path for `SAFE_STOPPED` runs (finding 6).
- No code-coverage gate, no load/performance testing beyond a single concurrency test, no mutation testing
  — all deliberately out of scope for this timeframe (see `docs/TESTING.md`).
- The product itself (`url-shortener`) has known, documented limitations unrelated to the orchestrator: an
  in-memory per-instance rate limiter, no authentication, H2 as the only datastore, unbounded click-event
  retention — see `url-shortener/README.md` and `docs/RISKS.md`.
