I couldn't read the repo in this run, so this design relies on the facts the reviewer gave. Two things are still unconfirmed: the name of the existing error-body schema and how expired links are created in tests. The relevant tasks say to reuse what already exists for both.

## Design overview
The revised design matches the real codebase: Spring Data JDBC, `schema.sql`, the `/api/v1` prefix, `LinkService.resolve`, `ApiExceptionHandler`, `AppProperties` and `ClockConfig`. It adds a `click_event` table that stores no personal data, writes to it in the existing redirect call chain, and adds a read-only endpoint, `GET /api/v1/links/{code}/analytics`.

Key decisions and their trade-offs:

1. **Extend `LinkService.resolve` instead of replacing it.**
   - Add an overload, `resolve(String code, ClickMetadata meta)`.
   - The existing `resolve(String code)` delegates to it with `ClickMetadata.none()`, so current callers and tests still compile.
   - The event is inserted only after the existing lookup, the `Link.isExpired` check and the counter increment. `LinkNotFoundException` (404) and `LinkExpiredException` (410) are thrown before the insert, so AC3 and AC4 come from the existing control flow.
   - The method becomes `@Transactional`, so the counter and the event commit together (AC2).
   - *Trade-off:* if the event insert fails, the redirect fails. We get consistency, but each redirect does one extra small indexed insert. Async recording is the scaling path.
2. **Use Spring Data JDBC, not JPA.**
   - `ClickEvent` is an immutable `record` annotated with `@Table("click_event")` and `@Column` from `org.springframework.data.relational.core.mapping`.
   - `ClickEventRepository extends CrudRepository<ClickEvent, Long>`, with one plain-SQL `@Query` for the date range.
   - `UserAgentCategory` is stored as its enum name (the Spring Data JDBC default).
3. **Aggregate in Java over the rows in range, not with SQL `GROUP BY`.**
   - This avoids H2-specific date casting and projection mapping in Spring Data JDBC.
   - UTC day bucketing is explicit (`Instant` → `LocalDate` at `ZoneOffset.UTC`).
   - *Trade-off:* memory use is O(events in range). It is bounded by `app.analytics.max-range-days` (default 366). Pre-aggregation is the scaling path.
4. **Append the schema to `schema.sql`.**
   - Use `CREATE TABLE IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`, which is idempotent under `spring.sql.init.mode=always`.
   - There is no migration file and no backfill. History starts at deployment.
   - *Trade-off:* no versioned rollback. The rollback is a manual `DROP TABLE click_event`.
5. **Put config in `AppProperties`.**
   - Add a nested static `Analytics` class with getters and setters, bound to `app.analytics.*`, following the existing `RateLimit` and `Link` pattern.
   - Fields: `defaultRangeDays=30`, `maxRangeDays=366`, `topReferrersLimit=10`.
   - No new properties class.
6. **Reuse the `ClockConfig` bean.**
   - `AnalyticsService` uses it for "today" and `LinkService` uses it for `clickedAt`. No new clock bean.
   - Tests override it with a fixed `Clock` through a `@TestConfiguration` with `@Primary`.
7. **Keep analytics working for expired links.**
   - `AnalyticsService` looks up the link through `LinkRepository` by code, with no expiry check. An expired link still returns its history.
   - An unknown code throws the existing `LinkNotFoundException`, which gives 404 with the standard error body (AC8).
8. **Store no personal data (AC10).**
   - Only the referrer host (lowercased, http/https only, at most 253 characters, otherwise null) and the user-agent category are stored.
   - Raw `User-Agent` and `Referer` values are never stored or logged.
9. **Bot classification is coarse and best-effort.** All rules sit in `UserAgentClassifier`:
   - null or blank → `OTHER`
   - contains `bot`, `crawler`, `spider`, `slurp`, `curl`, `wget`, `python-requests` or `headless` (case-insensitive) → `BOT`
   - contains `mozilla/` → `BROWSER`
   - anything else → `OTHER`
10. **Parameter defaults and validation.**
    - `to` defaults to today (UTC).
    - `from` defaults to `to − (defaultRangeDays − 1)`.
    - `from > to`, or a range longer than `maxRangeDays`, throws `InvalidAnalyticsRangeException`, which `ApiExceptionHandler` maps to 400.
    - A malformed date raises `MethodArgumentTypeMismatchException`, which also maps to 400 with the standard error body (AC9).
    - `totalClicks` covers only the requested range, after the bot filter (AC5, AC7).
    - `topReferrers` is sorted by count descending, then host ascending, and skips null hosts.

**Shared contracts** (the code and tests stages both build against these, so they can run in parallel):
- `analytics.UserAgentCategory { BROWSER, BOT, OTHER }`
- `analytics.UserAgentClassifier#classify(String) : UserAgentCategory` (`@Component`)
- `analytics.ReferrerHostExtractor#extract(String) : String` (`@Component`)
- `link.ClickMetadata(String userAgent, String referer)`, with `static none()`
- `analytics.ClickEvent(@Id Long id, Long linkId, Instant clickedAt, String referrerHost, UserAgentCategory uaCategory)`
- `ClickEventRepository#findInRange(Long linkId, Instant fromInclusive, Instant toExclusive) : List<ClickEvent>`
- `AnalyticsService#getAnalytics(String code, LocalDate from, LocalDate to, boolean excludeBots) : LinkAnalyticsResponse`
- DTO records:
  - `LinkAnalyticsResponse(code, from, to, excludeBots, totalClicks, List<DailyClicks> daily, List<ReferrerCount> topReferrers, Map<UserAgentCategory, Long> userAgents)`
  - `DailyClicks(LocalDate date, long clicks)`
  - `ReferrerCount(String host, long clicks)`

README and `docs/RISKS.md` updates belong to the docs stage. They cover:
- the counter and the event totals differ for links created before deployment
- unbounded retention
- the synchronous write
- the coarse bot detection

## Task plan
| id | task | depends on | stage |
|---|---|---|---|
| C1 | Append the `click_event` DDL and index to `src/main/resources/schema.sql` (see Schema changes) | — | code |
| C2 | Add `UserAgentCategory` enum and `UserAgentClassifier` with the rules above | — | code |
| C3 | Add `ReferrerHostExtractor`: http/https only, lowercased host, null if missing, malformed or longer than 253 characters | — | code |
| C4 | Add `ClickEvent` record (Spring Data JDBC `@Table`/`@Column`) and `ClickEventRepository extends CrudRepository<ClickEvent, Long>` with `@Query("SELECT * FROM click_event WHERE link_id = :linkId AND clicked_at >= :from AND clicked_at < :to")` | C1, C2 | code |
| C5 | Add `ClickMetadata` record. Add the `LinkService.resolve(String, ClickMetadata)` overload, make it `@Transactional`, and save the `ClickEvent` (Clock `now`, classified UA, extracted host) after the existing expiry check and counter increment. `resolve(String)` delegates with `none()` | C2, C3, C4 | code |
| C6 | `RedirectController` (`GET /{code:[0-9A-Za-z]{1,16}}`): read `@RequestHeader(value = USER_AGENT, required = false)` and `REFERER` (optional), call the new overload, and keep the response unchanged | C5 | code |
| C7 | Add nested static `Analytics` class to `AppProperties` (`defaultRangeDays=30`, `maxRangeDays=366`, `topReferrersLimit=10`), with defaults under `app.analytics` in the application config | — | code |
| C8 | Add DTO records `LinkAnalyticsResponse`, `DailyClicks` and `ReferrerCount`, and `InvalidAnalyticsRangeException` | — | code |
| C9 | Add `AnalyticsService`: resolve the link by code with no expiry check (404 if not found), apply defaults, validate the range, query `[from 00:00Z, to+1 00:00Z)`, apply the bot filter, fill zero days, build referrer top-N and UA counts (all three categories, defaulting to 0), sum `totalClicks` | C4, C7, C8 | code |
| C10 | Add `AnalyticsController`: `GET /api/v1/links/{code}/analytics` with `from`/`to` (`@DateTimeFormat(iso = DATE)`, optional) and `excludeBots` (default `false`) | C9 | code |
| C11 | `ApiExceptionHandler`: map `InvalidAnalyticsRangeException` and `MethodArgumentTypeMismatchException` to 400 with the standard error body. Confirm that the `LinkNotFoundException` 404 mapping applies | C8 | code |
| C12 | Update the module's existing OpenAPI document (wherever `/stats` is documented) with the fragment below | C10 | code |
| C13 | Check that the existing rate limiter covers `/api/v1/links/*/analytics`. Extend its path matching only if it doesn't | C10 | code |
| T1 | `UserAgentClassifierTest`: browser, bot and curl user agents, plus null and blank (AC7 basis) | — | tests |
| T2 | `ReferrerHostExtractorTest`: `https://a.com/x` → `a.com`; uppercase; null, blank, malformed, `ftp:` and over-long input → null (AC6, AC10) | — | tests |
| T3 | `LinkServiceTest` (unit, mocked repositories, fixed Clock): one event saved with the correct fields on success (AC1); counter still incremented (AC2); no save on `LinkNotFoundException` (AC3) or `LinkExpiredException` (AC4); the `resolve(String)` overload still works | — | tests |
| T4 | `AnalyticsServiceTest` (mocked repositories, fixed Clock): daily series with zero fill (AC5), referrer ordering and hosts only (AC6), `excludeBots` totals (AC7), unknown code (AC8), `from > to` and over-max range (AC9), defaults | — | tests |
| T5 | `RedirectAnalyticsIT` (`@SpringBootTest` + MockMvc on H2): redirect stores exactly one row with the expected values (AC1); `/api/v1/links/{code}/stats` shows N+1 (AC2); unknown code gives 404 and no rows (AC3); expired link gives 410 and no rows, using the existing expiry test fixture (AC4); stored row and `INFORMATION_SCHEMA.COLUMNS` have no IP, full UA or full referrer columns, and `referrer_host` holds only the host (AC10) | — | tests |
| T6 | `AnalyticsControllerIT`: seed events through `ClickEventRepository` with explicit UTC timestamps, then check the AC5 response, AC6 ordering, AC7 with and without `excludeBots`, AC8 404 with the standard error body, and AC9 400 for `from > to` and for `from=2026-13-01` | — | tests |
| T7 | `SchemaIT`: the `click_event` table and `idx_click_event_link_time` on `(link_id, clicked_at)` exist in `INFORMATION_SCHEMA`; the app restarts with `schema.sql` applied twice (idempotent) (AC11) | — | tests |
| T8 | Keep the existing redirect, stats and expiry tests green with no changes to their assertions (AC2 regression, AC11 `./mvnw verify`) | — | tests |

Test tasks depend only on the shared contracts above. The build+test join stage runs `./mvnw verify` over both stages' outputs (AC11).

## API changes
```yaml
paths:
  /api/v1/links/{code}/analytics:
    get:
      summary: Per-link click analytics (read-only)
      description: >
        Click history recorded from deployment onward, bucketed by UTC day.
        Available for expired links. It can differ from the all-time counter
        returned by /api/v1/links/{code}/stats.
      operationId: getLinkAnalytics
      parameters:
        - name: code
          in: path
          required: true
          schema: { type: string, pattern: '^[0-9A-Za-z]{1,16}$' }
        - name: from
          in: query
          required: false
          description: Inclusive UTC start date. Default is to minus 29 days.
          schema: { type: string, format: date }
        - name: to
          in: query
          required: false
          description: Inclusive UTC end date. Default is today (UTC).
          schema: { type: string, format: date }
        - name: excludeBots
          in: query
          required: false
          schema: { type: boolean, default: false }
      responses:
        '200':
          description: Analytics for the requested range
          content:
            application/json:
              schema: { $ref: '#/components/schemas/LinkAnalyticsResponse' }
        '400':
          description: from later than to, range over the configured maximum (default 366 days), or malformed date
          content:
            application/json:
              # reuse the existing error schema component already referenced by /stats
              schema: { $ref: '#/components/schemas/ErrorResponse' }
        '404':
          description: Unknown short code
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
        '429':
          description: Rate limit exceeded (existing limiter)
components:
  schemas:
    LinkAnalyticsResponse:
      type: object
      required: [code, from, to, excludeBots, totalClicks, daily, topReferrers, userAgents]
      properties:
        code: { type: string }
        from: { type: string, format: date }
        to: { type: string, format: date }
        excludeBots: { type: boolean }
        totalClicks: { type: integer, format: int64, description: Clicks in range after the bot filter }
        daily:
          type: array
          description: One entry per day from "from" to "to", zero-filled
          items: { $ref: '#/components/schemas/DailyClicks' }
        topReferrers:
          type: array
          description: Up to 10 referrer hosts, ordered by clicks descending then host ascending
          items: { $ref: '#/components/schemas/ReferrerCount' }
        userAgents:
          type: object
          required: [BROWSER, BOT, OTHER]
          properties:
            BROWSER: { type: integer, format: int64 }
            BOT: { type: integer, format: int64 }
            OTHER: { type: integer, format: int64 }
    DailyClicks:
      type: object
      required: [date, clicks]
      properties:
        date: { type: string, format: date }
        clicks: { type: integer, format: int64 }
    ReferrerCount:
      type: object
      required: [host, clicks]
      properties:
        host: { type: string, example: a.com }
        clicks: { type: integer, format: int64 }
```
`GET /{code}` and `GET /api/v1/links/{code}/stats` keep their external contracts.

## Schema changes
```sql
-- appended to url-shortener/src/main/resources/schema.sql (spring.sql.init.mode=always; idempotent)
CREATE TABLE IF NOT EXISTS click_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    link_id       BIGINT       NOT NULL,
    clicked_at    TIMESTAMP    NOT NULL,          -- UTC instant
    referrer_host VARCHAR(253) NULL,              -- host only, never the full URL
    ua_category   VARCHAR(16)  NOT NULL,
    CONSTRAINT fk_click_event_link FOREIGN KEY (link_id) REFERENCES links (id) ON DELETE CASCADE,
    CONSTRAINT ck_click_event_ua CHECK (ua_category IN ('BROWSER', 'BOT', 'OTHER'))
);

CREATE INDEX IF NOT EXISTS idx_click_event_link_time ON click_event (link_id, clicked_at);
```
There are no changes to `links`. No IP, full user-agent or full referrer columns are added.

## Approvals needed
- **Public API (new endpoint):** `GET /api/v1/links/{code}/analytics`, which adds the `from`, `to` and `excludeBots` parameters and the `LinkAnalyticsResponse`, `DailyClicks` and `ReferrerCount` schemas. It is additive and read-only. Approve the defaults too: a 30-day range, a 366-day maximum, the top-10 referrers, and data still returned for expired links.
- **Public API (unchanged contract, changed behaviour):** `GET /{code}` now writes a `click_event` row in the same transaction as the counter. Status codes and headers stay the same. Approve the trade-off that a failed event insert fails the redirect.
- **Schema:** new table `click_event` (foreign key to `links(id)` with `ON DELETE CASCADE`, check constraint on `ua_category`) and new index `idx_click_event_link_time`, both appended to `schema.sql`. There is no backfill, so history starts at deployment, and retention is unbounded.
- **Dependencies:** none added.
- **Configuration (for information):** new keys `app.analytics.default-range-days`, `app.analytics.max-range-days` and `app.analytics.top-referrers-limit`.