## Design overview

The design follows the reviewer's corrections about the real `url-shortener` code: Spring Data JDBC, the immutable `Link` record, `schema.sql` with no Flyway, `AppProperties` under the `app` prefix, and `RedirectController`, `LinkService` and `ApiExceptionHandler`. It adds expiry as one nullable column and keeps every change small and additive.

**Assumptions to verify.** The reviewer's correction (5) was cut off after "maps InvalidUrlException,". Because of that, a reviewer should confirm these names before code is merged:
- The not-found exception, assumed to be `LinkNotFoundException` mapped to 404.
- The rate-limit exception, assumed to be `RateLimitExceededException` mapped to 429.
- The create request and response records, assumed to be `CreateLinkRequest` and `CreateLinkResponse`.
- The create path, assumed to be `POST /api/links`.
- The location of the OpenAPI spec file.

Key decisions and their trade-offs:

1. **`Link` record gets a new last component, `@Column("expires_at") Instant expiresAt`.**
   - The canonical constructor is marked `@PersistenceCreator`.
   - The old 5-argument constructor is kept and delegates with `expiresAt = null`, so existing call sites and tests still compile.
   - *Trade-off:* the record has one extra constructor, but the change does not ripple through the code and the code and test stages can work in parallel.
2. **Expiry is checked in `LinkService.resolve`, not in SQL.**
   - The flow is: `findByShortCode`, then throw 404 if missing, then throw `LinkExpiredException` if `expiresAt != null && !clock.instant().isBefore(expiresAt)`, and only then call `incrementClickCount`.
   - *Trade-off:* this costs one read before the atomic increment, which the current flow already does. It guarantees that clicks on expired links are never counted (AC 4, 5, 6, 8).
3. **Rate limiting stays where it is.** It must run before the lookup (AC 11). The code task only confirms this order and does not move it.
4. **`ttlSeconds` is validated by hand in `LinkService`.**
   - Values outside 60–31536000 throw a new `InvalidTtlException`, which maps to 400. No row is saved.
   - This mirrors the existing `InvalidUrlException` pattern instead of relying on `@Min`/`@Max`.
   - *Trade-off:* the rule lives in code rather than annotations, but it does not depend on `spring-boot-starter-validation` being present, so no dependency risk.
5. **Configuration goes in a nested `AppProperties.Link` group.**
   - `app.link.default-ttl=P30D`, `app.link.cleanup-interval=PT1H`, `app.link.cleanup-grace=P7D`, all as `Duration`.
   - Any zero or negative value fails at startup with `IllegalStateException`, checked in the group's constructor.
   - *Trade-off:* this keeps the one-properties-class convention, but `AppProperties` grows.
6. **A UTC `Clock` bean (`Clock.systemUTC()`) is injected** into `LinkService` and the cleanup job.
   - `createdAt` and `expiresAt` both come from the same `clock.instant()` call, so `expiresAt = createdAt + ttl` exactly (AC 1, 2).
   - Tests use `Clock.fixed`.
7. **Cleanup is `ExpiredLinkCleanupJob`.**
   - It runs with `@Scheduled(fixedDelayString = "${app.link.cleanup-interval:PT1H}")` and is turned on by a new `SchedulingConfig` with `@EnableScheduling`.
   - It calls `deleteExpiredBefore(clock.instant().minus(grace))`, a single atomic `@Modifying @Query` delete in plain SQL, and logs the number of rows deleted.
   - There is no child clicks table, so no cascade is needed.
   - *Trade-off:* it is one unbatched statement, which is fine for H2 and a single instance. It does not use ShedLock (out of scope).
8. **The migration is appended to `schema.sql`.**
   - It uses `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`. Both are idempotent under `spring.sql.init.mode=always`, so existing H2 file databases gain the column and existing rows stay `NULL` (AC 8, 12).
   - The `CREATE TABLE` statement is left unchanged, so the `ALTER` is the single source of truth for the column.
   - The column is plain `TIMESTAMP`. Spring Data JDBC writes the `Instant` and the cutoff parameter through the same conversion, so comparisons stay consistent. Tests run with UTC.

## Task plan

| id | task | depends on | stage |
|----|------|------------|-------|
| C1 | Append the idempotent `ALTER TABLE links ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP` and `CREATE INDEX IF NOT EXISTS idx_links_expires_at` to `src/main/resources/schema.sql`; leave `CREATE TABLE` unchanged (AC 12) | – | code |
| C2 | Add the nested `Link` group (`defaultTtl`, `cleanupInterval`, `cleanupGrace` as `Duration`, rejecting values ≤ 0) to `AppProperties`, and set the defaults in `application.yml` under `app.link.*` | – | code |
| C3 | Add `ClockConfig` with a `@Bean Clock clock()` returning `Clock.systemUTC()`, and `SchedulingConfig` with `@EnableScheduling` | – | code |
| C4 | Add the `@Column("expires_at") Instant expiresAt` component to the `Link` record, mark the canonical constructor `@PersistenceCreator`, and keep a 5-argument constructor that passes `null` | C1 | code |
| C5 | Add `@Modifying @Query("DELETE FROM links WHERE expires_at IS NOT NULL AND expires_at < :cutoff") int deleteExpiredBefore(@Param("cutoff") Instant cutoff)` to `LinkRepository` | C4 | code |
| C6 | Add `LinkExpiredException` and `InvalidTtlException`; in `ApiExceptionHandler`, map them to 410 and 400 using the existing JSON error body, with no `Location` header; leave the 404 mapping unchanged (AC 3, 6, 7) | – | code |
| C7 | Create path: add an optional `Long ttlSeconds` to the create request and an `Instant expiresAt` (ISO-8601 UTC) to the create response. In `LinkService.create`, validate 60 ≤ ttl ≤ 31536000 before saving, compute `now = clock.instant()`, and set `createdAt = now` and `expiresAt = now + ttl or the default` (AC 1, 2, 3) | C2, C3, C4, C6 | code |
| C8 | Redirect path: in `LinkService.resolve`, look the code up, throw 404 if it is missing, throw `LinkExpiredException` if `now >= expiresAt`, and otherwise call `incrementClickCount` and return 302. Confirm the rate-limit check runs before the lookup in `RedirectController` or its filter (AC 4–8, 10, 11) | C3, C4, C6 | code |
| C9 | Add `ExpiredLinkCleanupJob` with `@Scheduled(fixedDelayString = "${app.link.cleanup-interval:PT1H}") void purge()`, which calls `deleteExpiredBefore(now - cleanupGrace)` and logs the count (AC 9, 10) | C2, C3, C5 | code |
| C10 | Update the OpenAPI spec with the `ttlSeconds` request field, the `expiresAt` response field, the 410 response on `GET /{code}`, and the new 400 cause (AC 13) | C7, C8 | code |
| T1 | `LinkServiceTest` with a fixed `Clock`: the default TTL gives T + 30 days; `ttlSeconds = 3600` gives T + 1 hour; TTLs of 59, 0, −1 and 31536001 are rejected and `save` is never called (AC 1, 2, 3) | C7 | tests |
| T2 | `LinkServiceTest` resolve cases: `now < expiresAt` redirects and increments the click count; `now == expiresAt` and `now > expiresAt` both throw `LinkExpiredException` with no increment; `expiresAt == null` always redirects; an unknown code throws not-found (AC 4–8) | C8 | tests |
| T3 | Repository test (`@DataJdbcTest`): rows expired 8 days ago, expired 6 days ago, not yet expired, and `NULL`, with a 7-day grace; check that only the 8-day row is deleted and the method returns 1 (AC 9) | C5 | tests |
| T4 | `ExpiredLinkCleanupJobTest`: with a fixed clock, `purge()` passes `cutoff = now − grace` to the repository (AC 9) | C9 | tests |
| T5 | Schema test: insert a row without `expires_at`, run `schema.sql` again, and check that the row is kept, `expires_at` is `NULL`, and `idx_links_expires_at` exists in `INFORMATION_SCHEMA.INDEXES` (AC 12) | C1 | tests |
| T6 | Integration test (`@SpringBootTest` + MockMvc, with a test `@Primary` mutable clock): 201 with `expiresAt`; 302; 404 for an unknown code; 410 with a JSON body, no `Location` header and an unchanged click count; 404 after `purge()`; 429 for an expired code once the requester is at the limit (AC 1, 4, 6, 7, 10, 11) | C7, C8, C9 | tests |
| T7 | `AppPropertiesTest`: defaults bind to P30D, PT1H and P7D; a zero or negative duration fails at startup | C2 | tests |
| T8 | Update existing tests that build `Link` or the create response only where the new fields change what they assert, then check that `./mvnw verify` passes and coverage includes the new classes (AC 13 spec check, AC 14) | C4, C10, T1–T7 | tests |

## API changes

```yaml
paths:
  /api/links:
    post:
      summary: Create a short link
      description: >
        Every new link now expires. If ttlSeconds is omitted, app.link.default-ttl (P30D)
        is used. Links created before this release never expire.
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateLinkRequest'
      responses:
        '201':
          description: Link created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/CreateLinkResponse'
        '400':
          description: Invalid URL or ttlSeconds outside 60..31536000
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
  /{code}:
    get:
      summary: Redirect to the original URL
      parameters:
        - name: code
          in: path
          required: true
          schema:
            type: string
      responses:
        '302':
          description: Redirect to the original URL; the click count is incremented
          headers:
            Location:
              schema:
                type: string
                format: uri
        '404':
          description: Unknown code, or an expired link that has already been cleaned up
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '410':
          description: Link has expired (now >= expiresAt). No Location header; the click is not counted.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
        '429':
          description: Rate limit exceeded (checked before the expiry check)
components:
  schemas:
    CreateLinkRequest:
      type: object
      required: [url]
      properties:
        url:
          type: string
          format: uri
        ttlSeconds:
          type: integer
          format: int64
          minimum: 60
          maximum: 31536000
          description: Optional. Overrides app.link.default-ttl.
    CreateLinkResponse:
      type: object
      properties:
        code:
          type: string
        shortUrl:
          type: string
          format: uri
        expiresAt:
          type: string
          format: date-time
          description: ISO-8601 UTC instant after which the link returns 410.
```

## Schema changes

The following is appended to `url-shortener/src/main/resources/schema.sql`. It is idempotent and runs on every startup.

```sql
ALTER TABLE links ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_links_expires_at ON links(expires_at);
```

There is no backfill: existing rows keep `expires_at = NULL`, which means they never expire.

## Approvals needed

- **Public API: `POST /api/links`.**
  - Adds the optional `ttlSeconds` request field (60–31536000; values outside this range return 400) and the `expiresAt` response field.
  - **Behaviour change:** links created without `ttlSeconds` now expire after 30 days, where before they never did. This needs to go in the release notes.
- **Public API: `GET /{code}`.**
  - Adds a new `410 Gone` response.
  - Cleaned-up codes go back to returning `404`, and once cleaned up, a code may be given to a new link.
- **Schema: `links` table.**
  - Adds the nullable `expires_at TIMESTAMP` column and the `idx_links_expires_at` index through statements appended to `schema.sql` that are safe to run every startup.
- **Configuration contract.**
  - Adds `app.link.default-ttl`, `app.link.cleanup-interval` and `app.link.cleanup-grace`.
  - Adds a scheduled deletion job, turned on by `@EnableScheduling`, that permanently deletes data.
- **Dependencies: none.**
  - Everything uses what Spring Boot, Spring Data JDBC and H2 already provide. Flyway, Liquibase, ShedLock and the validation starter are not added.