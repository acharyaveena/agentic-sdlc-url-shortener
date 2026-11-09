## Findings

The automated run safe-stopped at `build-test` before this stage ran. This check was performed by hand,
after human review, using the same rules as the automated `policy-scan` stage, against the recovered
(human-merged) code rather than the orchestrator's broken output.

| rule | status | evidence |
|------|--------|----------|
| secret-scan | PASS | No credential-like patterns found in `url-shortener/src` or configuration files. No new config keys introduced hold secret-like values (`app.link.default-ttl`, `app.link.cleanup-interval`, `app.link.cleanup-grace` are all ISO-8601 durations). |
| dependency-allowlist | PASS | No dependency was added. `url-shortener/pom.xml` is unchanged by this feature: `spring-boot-starter-web`, `spring-boot-starter-data-jdbc`, `spring-boot-starter-validation`, `com.h2database:h2`, `spring-boot-starter-test`, `spring-boot-maven-plugin` — all already on the allowlist. The orchestrator's own code stage attempted to rewrite `pom.xml` and would have silently dropped `spring-boot-starter-validation`; that rewrite was discarded during manual recovery. |
| schema-change-approval | PASS | The schema change (`expires_at` column + index) was explicitly flagged in the requirements document and approved as part of the `design-and-schema` approval gate (see `../approvals.md`, decided `2026-11-09T23:23:55Z`, revision 3). The change actually shipped is additive only: `ALTER TABLE links ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP` and `CREATE INDEX IF NOT EXISTS idx_links_expires_at`, appended after the original `CREATE TABLE IF NOT EXISTS links` block, which is untouched. This differs from what the code stage actually generated (which renamed `long_url` to `original_url`, widened `short_code`, and dropped both existing constraints) — that output was discarded, not approved, and never merged. |
| write-path restriction | PASS | All changes are inside `url-shortener/`. No files outside that module were touched. |

## Verdict
PASS (performed manually against the human-recovered code; see `../walkthrough.md` for why the automated
stage never ran and why its own output would have failed several of these checks had it been merged
as-is).
