## Summary
"Make analytics better" was submitted as a deliberately vague brownfield request against the existing
URL shortener. The requirements stage handled the ambiguity well: 10 documented ambiguities, explicit
assumptions for each, and 11 acceptance criteria, all approved without a re-plan. The design stage then
needed a full reject-and-replan cycle — its first revision assumed a completely different stack (JPA,
Flyway, wrong API prefix and class names) because it was never given the real repository to read. A
detailed rejection with concrete facts about the real codebase produced an approved, well-corrected
revision 3. The code and tests stages then both ran and were marked `SUCCEEDED`, but the code stage's
actual output silently abandoned the approved design's integration contract in favor of a different,
less consistent architecture that also contained a real bug. The feature was integrated by hand instead,
reusing most of the tests stage's suite and the code stage's self-contained, correct files. `./mvnw verify`
now passes for the whole repository: 283 orchestrator tests, 162 url-shortener tests (68 new for this
feature).

## Validation results
- **Requirements:** reviewed and approved by a human on the first pass (see `approvals.md`). 10
  ambiguities each with a stated assumption, 11 acceptance criteria (see `artifacts/requirements.md`).
- **Impact analysis:** ran automatically ahead of the design approval gate; correctly flagged the schema
  and API changes as needing approval (see `artifacts/impact-analysis.md`).
- **Design:** required one reject-and-replan. Revision 1 assumed JPA, Flyway and the wrong API prefix
  (`/api/links` instead of the real `/api/v1/links`) — none of which match the real codebase, because the
  design stage is never given the real repository files for a brownfield change. It was rejected with
  specific corrective facts (real persistence style, real schema approach, real class names, real
  `AppProperties` nesting convention). Revision 2 (also produced via an explicit `--replan` of
  impact-analysis, needed because an earlier CLI invocation in this run accidentally dropped back to mock
  agent mode — see Risks below) was superseded automatically once impact-analysis changed. Revision 3,
  built against the corrective feedback, was approved (see `approvals.md` for the full rejection reason).
- **Code and tests stages:** both reported `SUCCEEDED`. The tests stage built correctly against the
  approved `LinkService.resolve(code, ClickMetadata)` contract. The code stage did not implement that
  contract at all: it built a `ClickRecordingFilter`/`ClickRecorder` pair that records clicks out-of-band
  after the redirect response is already sent, catching and silently logging any insert failure instead of
  failing the redirect — the opposite of the approved trade-off. It also queried
  `SELECT id FROM links WHERE code = ?` in two different files; the real column is `short_code`, so this
  would have failed at runtime even if the build had compiled. None of this output was merged; see
  `../walkthrough.md` for the full defect list and `agent-failed/21bf5f2f` for the preserved evidence.
- **Build and tests (after manual recovery):** `./mvnw -pl url-shortener verify` — 162 of 162 tests pass,
  including the tests stage's own suite (daily-series bucketing, top-referrer ordering, bot filtering,
  date-range validation, no-personal-data schema checks, expired-link history) almost entirely unchanged,
  plus 2 more wrong-column-name bugs and one arithmetic bug fixed in the generated tests themselves, and
  three `*IT.java` files renamed to `*Test.java` (same no-Failsafe-plugin issue found in earlier scenarios).
  Whole-repo `./mvnw verify` also passes (283 orchestrator tests).
- **Policy scan:** performed manually after the automated run stopped early; see `artifacts/policy-scan.md`.
  No secrets, no new dependencies, schema change matches what was actually approved, and — the specific
  thing this scenario's evidence is most useful for — a direct comparison between what the design approved
  and what the code stage actually built.

## Risks and trade-offs
- **Code-stage success does not mean design conformance — the central finding of this scenario.** Unlike
  the brownfield scenario (where generated files were corrupted and obviously broken), this run's code
  stage produced new files only, in a sensibly named package, that looked clean at a glance. The actual
  defect was architectural: it quietly replaced the approved, reviewed integration point with a different
  one that has weaker consistency guarantees and a real runtime bug. A reviewer who checked only "did the
  build pass" and "are there new files in the wrong place" (the brownfield scenario's lesson) would still
  have missed this. The only way to catch it was reading the diff against the approved design line by line.
- **A CLI operator mistake caused a redundant re-plan.** The `--approve=...:requirements` command that
  advanced this run into impact-analysis and design was run without repeating
  `--orchestrator.agent.mode=copilot`, so those two stages briefly ran against the mock agent runtime
  (visible as literal "(mock output)" placeholder text) before being caught and re-planned with the flag
  included. This is an operator error, not an orchestrator defect, but it is the same category of mistake
  as the brownfield scenario's CLI-quoting failure: this CLI's statelessness between invocations (each
  subcommand is a fresh JVM reading `application.yml` from scratch) makes it easy to forget a flag that
  must be repeated on every single call for a given run.
- **Cross-stage drift happened a third time.** Greenfield had a constructor mismatch between code and
  tests. Brownfield had code-gen regenerating whole files from scratch. This scenario has the code stage
  silently choosing a different integration architecture than what tests (and the human reviewer) expected
  from the approved design. All three are instances of the same root problem: two independent model calls
  against one design, with no mechanism to catch disagreement before the tests stage's compile failure.
- **The orchestrator still cannot resume a `SAFE_STOPPED` run after an out-of-band fix.** Docs, the policy
  scan and this summary were written by hand, same as the previous two scenarios. Recorded in `docs/RISKS.md`.

## Limitations
- Like brownfield, the automated pipeline did not produce usable code for this scenario; a human
  implemented the actually-approved design, using the tests stage's suite as the primary source of
  reusable work (after fixing its bugs) and the code stage's self-contained files (classifier, extractor,
  DTOs, repository) where they were genuinely correct.
- Coverage was not measured (no JaCoCo gate in this design); test count and real execution are the evidence
  of thoroughness here.

## Recommendation
RELEASE WITH CONDITIONS: merge once a reviewer has read commit `37c4a34` (`fix(human-review): add link
analytics`) and confirmed the hand-written implementation matches the approved design (same-transaction
click recording, no personal data stored, 30-day default range, 366-day cap, top-10 referrers). No further
automated gate blocks it; `./mvnw verify` passes for the whole repository. This scenario's real value is
the risk finding above — "an approved design is not self-enforcing" — and it should be cited alongside the
brownfield scenario's finding in `docs/RISKS.md` and `docs/SUMMARY.md`.
