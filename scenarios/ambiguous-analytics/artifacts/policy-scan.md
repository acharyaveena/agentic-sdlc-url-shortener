## Findings

The automated run safe-stopped at `build-test` before this stage ran. This check was performed by hand,
after human review, using the same rules as the automated `policy-scan` stage, against the recovered
(human-merged) code rather than the orchestrator's broken output.

| rule | status | evidence |
|------|--------|----------|
| secret-scan | PASS | No credential-like patterns found in `url-shortener/src` or configuration files. No new config keys hold secret-like values (`app.analytics.default-range-days`, `.max-range-days`, `.top-referrers-limit` are plain integers). |
| dependency-allowlist | PASS | No dependency was added. `url-shortener/pom.xml` is byte-for-byte unchanged by this feature. This matters here specifically because the orchestrator's own code stage did NOT touch `pom.xml` this time (unlike brownfield), confirming it correctly recognised no new dependency was needed even while deviating architecturally elsewhere. |
| schema-change-approval | PASS | The schema change (new `click_event` table + index) was explicitly flagged in the requirements document and approved as part of the `design-and-schema` approval gate on its third revision (see `../approvals.md`, decided `2026-11-10T00:51:07Z`). The change actually shipped is additive only: `CREATE TABLE IF NOT EXISTS click_event (...)` and `CREATE INDEX IF NOT EXISTS idx_click_event_link_time`, appended after the existing `links` table block and its link-expiry additions, all of which are untouched. |
| write-path restriction | PASS | All changes are inside `url-shortener/`. No files outside that module were touched. |
| design conformance (not an automated PolicyGuard rule, but checked here because this scenario's own evidence shows why it should be) | FAIL in the orchestrator's own output, PASS after recovery | The code stage's generated output did not implement the approved design's `LinkService.resolve(code, ClickMetadata)` contract at all; it built a parallel, non-transactional `ClickRecordingFilter`/`ClickRecorder` pair instead, contradicting the approved consistency trade-off and querying a non-existent `code` column. That output was discarded. The merged code implements the design exactly as approved. |

## Verdict
PASS (performed manually against the human-recovered code; see `../walkthrough.md` for the full account of
why the automated code stage's output could not be used as-is despite reporting `SUCCEEDED`).
