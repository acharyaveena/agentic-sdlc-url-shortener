## Affected modules and classes
I could not read the code, so the class names below are assumptions. A reviewer should map each one to the real class.

- **Redirect controller** (for example `RedirectController`, `GET /{code}`): it must pass the `Referer` and `User-Agent` request headers to the service.
- **Link service** (for example `LinkService.resolve`/`recordClick`): it must save a click event in the same transaction as the existing counter increment, and only after the link is found and confirmed not expired.
- **Link repository / `Link` entity**: no field changes. The counter column stays as it is. Reviewers should check that the current increment is atomic (an `UPDATE ... SET clicks = clicks + 1` or an equivalent lock).
- **New `ClickEvent` entity and `ClickEventRepository`**: the JPA mapping for `click_event`, plus aggregate queries for daily counts, top referrer hosts, and totals with an optional bot filter.
- **New `UserAgentClassifier`**: maps a raw user agent to `BROWSER`, `BOT` or `OTHER` and never stores the raw string.
- **New `ReferrerHostExtractor`**: takes the host from the `Referer` header. It returns null if the header is missing or malformed, and lowercases the host.
- **New `AnalyticsController` and `AnalyticsService`**: serve `GET /api/links/{code}/analytics`. They validate dates, fill the daily series with zero-count days, and build the response DTO.
- **New DTOs** (`LinkAnalyticsResponse`, `DailyClicks`, `ReferrerCount`): the response contract.
- **Global exception handler** (for example `@ControllerAdvice`): reuse the existing 404 handling for unknown codes. Add 400 mappings for `from > to`, `MethodArgumentTypeMismatchException` and date parse errors, using the standard error body.
- **Expiry check** (from the brownfield link-expiry change): the event must be recorded after the expiry check, so expired links record nothing (AC4). Reviewers should verify where this check lives today.
- **Schema migration**: a new migration file. Reviewers should verify whether the module uses Flyway, Liquibase or `schema.sql`.
- **OpenAPI spec** (`openapi.yaml` or springdoc annotations): document the new endpoint, its parameters and its error responses.
- **Tests**: unit tests for the classifier, extractor and service, and integration tests (MockMvc with H2) for AC1 to AC10.
- **Docs**: the url-shortener README or API docs, plus `docs/RISKS.md` for the unbounded retention and synchronous-write limitations.

## Affected APIs
- **`GET /{code}` (redirect)**: the external contract does not change. Status codes, the `Location` header and the expiry and 404 behaviour stay the same. The only internal change is an extra insert on the redirect path. Fully backward compatible.
- **Existing link read endpoints that return the click count** (for example `GET /api/links/{code}`): the response does not change. The counter stays the source of truth for the existing `clicks` field (AC2).
- **New `GET /api/links/{code}/analytics`**: an additive, read-only endpoint, so it is backward compatible.
  - Query parameters:
    - `from` and `to`: ISO `yyyy-MM-dd` dates, inclusive, in UTC. Defaults need a design decision; I suggest the last 30 days ending today.
    - `excludeBots`: boolean, default `false`.
  - `200` response body: `{ code, from, to, totalClicks, daily: [{date, clicks}], topReferrers: [{host, clicks}], userAgents: {BROWSER, BOT, OTHER} }`.
  - `404`: unknown code, with the standard error body.
  - `400`: `from > to` or a malformed date.
  - Expired links: the analytics endpoint should still return data for them (assumption). Reviewers should confirm this.
  - Open design decisions: whether `totalClicks` covers only the date range (AC5 implies it does), the top-referrer limit (suggest 10), and the maximum range length to prevent abuse (suggest 366 days).
- **Public API change**: this needs human approval under governance.

## Schema changes
- **New table `click_event`:**
  - `id BIGINT` primary key, auto-generated
  - `link_id BIGINT NOT NULL`, foreign key to the links table (`id`). Choose `ON DELETE CASCADE` if links can be deleted, and verify whether they can.
  - `clicked_at TIMESTAMP NOT NULL`, in UTC
  - `referrer_host VARCHAR(253) NULL`
  - `ua_category VARCHAR(16) NOT NULL`, with a check constraint `IN ('BROWSER','BOT','OTHER')`
- **Index:** `idx_click_event_link_time` on `(link_id, clicked_at)` (AC11).
- **No columns** for IP, the full user agent or the full referrer (AC10).
- **Existing tables:** no changes. The counter column on the links table stays.
- **Migration approach:**
  - Add one forward-only migration, for example `V{n}__create_click_event.sql`, numbered after the link-expiry migration.
  - No backfill; history starts at deployment.
  - Rollback is a `DROP TABLE click_event`, which is safe because nothing else depends on the table.
  - If the module uses `schema.sql` or `ddl-auto` instead of Flyway, the reviewer should confirm that the new table is created in the same way and that `ddl-auto` does not drift from the SQL.
- **Approval:** the schema change needs human approval (PolicyGuard schema-change rule).

## Data flows
**Before:**
1. A client calls `GET /{code}`.
2. The controller calls the service, which looks up the link.
3. The service returns 404 if the link is missing, or the expiry response if it has expired. Otherwise it increments the click counter.
4. The client gets a 302 to the target URL.
5. Analytics are limited to the single counter shown on the existing link endpoint.

**After, redirect (write path):**
1. A client calls `GET /{code}`.
2. The controller reads the `User-Agent` and `Referer` headers.
3. The service looks up the link. If it is missing, it returns 404 and writes no event (AC3). If it has expired, it returns the expiry response and writes no event (AC4).
4. Otherwise, in one transaction, the service:
   - increments the counter
   - classifies the user agent into a category
   - extracts the referrer host
   - inserts a `click_event` with `Instant.now()` in UTC
5. The transaction commits and the client gets a 302.
6. The raw header values are dropped and never persisted or logged.

**After, analytics (read path):**
1. A client calls `GET /api/links/{code}/analytics?from&to&excludeBots`.
2. The service validates the parameters (400 if invalid) and resolves the code (404 if unknown).
3. It runs grouped queries on `click_event` filtered by `link_id` and the `clicked_at` range `[from 00:00Z, to+1 00:00Z)`, with the bot filter applied if `excludeBots=true`:
   - count per day
   - count per `referrer_host` (non-null hosts, ordered by count descending, then host ascending)
   - count per `ua_category`
4. It fills missing days with 0, adds up `totalClicks`, and returns 200.

**Counter and events can differ:** the counter covers all time, while events start at deployment. For older links the two numbers will not match. This is expected and needs to be documented.

## Risks
- **Medium: redirect latency or failures from the extra synchronous insert.**
  - If the insert fails, the transaction rolls back and the redirect fails.
  - Mitigation: keep it to one small indexed insert and add an integration test of the redirect path. Decide whether to catch event-write failures (a failed event should not block the redirect), at the cost of an occasional event missing from the counter's total. Async recording is noted as the scaling path.
- **Medium: users confusing the counter with event totals for links created before deployment.**
  - Mitigation: document that history starts at deployment, and keep the analytics endpoint separate from the existing `clicks` field.
- **Medium: personal data leaking through logs, exception messages or debug output that include the raw `User-Agent` or `Referer`.**
  - Mitigation: never log raw header values. Add an integration test that inspects the schema and the stored row (AC10). Run the PolicyGuard secret/PII scan on the changes.
- **Medium: the read endpoint can be abused with very long date ranges or frequent calls, slowing H2.**
  - Mitigation: cap the range length and reuse the existing rate limiter on `/api/**`. The reviewer should verify that the rate limiter covers the new path.
- **Medium: unbounded table growth (no retention).**
  - Mitigation: record it as a limitation in `docs/RISKS.md`. The scaling path is daily pre-aggregation plus a purge job, which is out of scope here.
- **Low: date-boundary errors from time zones (local versus UTC bucketing) breaking AC5.**
  - Mitigation: store and bucket only in UTC, and use a fixed `Clock` bean in tests.
- **Low: H2-specific SQL for date bucketing (`CAST(clicked_at AS DATE)`) that will not port to another database.**
  - Mitigation: group with JPQL or a portable cast in the query, or bucket in Java over the returned rows.
- **Low: bot detection by simple user-agent patterns misclassifies some traffic.**
  - Mitigation: keep the list in one class with unit tests, and document that the classification is coarse and best-effort.
- **Low: concurrency on the counter increment under parallel clicks (already an issue before this change).**
  - Mitigation: the reviewer should verify that the increment is an atomic update. Events are insert-only, so they add no new lock contention.
- **Low: governance scope creep, given how vague the request is.**
  - Mitigation: these assumptions may be rejected at design approval. The schema and API changes are gated on human approval, and any re-plan has to pass those approvals again.