## Affected modules and classes
I wasn't given the existing code, so the class names below are assumptions. A reviewer should check them against `url-shortener/`.
- **Link entity (e.g. `Link` / `ShortLink`)**: add a nullable `expiresAt` (`Instant`) field mapped to `expires_at`.
- **Link repository (Spring Data JPA)**: add a batch delete for links where `expiresAt < cutoff`. Use one bulk `@Modifying` query rather than loading entities.
- **Create request DTO**: add an optional `ttlSeconds` (`Long`) with `@Min(60)` and `@Max(31536000)`.
- **Create response DTO**: add `expiresAt` as an ISO-8601 UTC string.
- **Shortening service (create path)**: work out `expiresAt = clock.instant() + (ttlSeconds ?: defaultTtl)`.
- **Redirect service/controller (resolve path)**: run the expiry check before click counting. Return 410 for expired links and 404 for unknown codes.
- **Click counter**: skip the increment for expired links. The likely change is the order of calls, not the counter itself.
- **Rate limiter (filter/interceptor)**: no logic change. Confirm it runs before the code lookup (AC 11).
- **Global exception handler (`@ControllerAdvice`)**: add a `LinkExpiredException` mapped to `410` with a JSON error body. Confirm the existing `404` mapping is unchanged.
- **New `LinkProperties` (`@ConfigurationProperties("shortener.link")`)**: holds `defaultTtl` (P30D), `cleanupInterval` (PT1H) and `cleanupGrace` (P7D).
- **New `ExpiredLinkCleanupJob`**: an `@Scheduled` job. It needs `@EnableScheduling` on the application or a config class.
- **New `Clock` bean (UTC)**: injected into the create, redirect and cleanup code. Tests swap in a fixed clock.
- **DB migration**: a new migration file, or an update to `schema.sql` if there is no Flyway/Liquibase (see Schema changes).
- **OpenAPI spec (`openapi.yaml` or springdoc annotations)**: add the new fields and the 410 response.
- **`application.yml`**: add the new properties with their defaults.
- **Tests**: unit tests for the expiry boundary and validation, integration tests for 201/302/404/410/429, a cleanup test and a migration test.

## Affected APIs
- **`POST` create endpoint (e.g. `/api/links`)**:
  - The request takes a new optional `ttlSeconds`. Values outside 60–31536000 return `400`.
  - The response has a new `expiresAt` field.
  - **Backward compatible.** Existing clients can leave out `ttlSeconds`. However, links they create now expire after 30 days, where before they never did. That behaviour change should be announced.
- **`GET /{code}` redirect**:
  - Adds a `410 Gone` response with a JSON body and no `Location` header.
  - `302`, `404` and `429` keep their current meaning.
  - **Mostly compatible.** Clients that treat any non-3xx as an error are fine. Clients that only handle 404 will see a new status code.
- **Configuration contract**: three new `shortener.link.*` properties.
- Both changes touch the public API and **need human approval**.

## Schema changes
- **Links table** (the name is assumed to be `links`; please verify):
  - Add `expires_at TIMESTAMP NULL`. Store it in UTC; use `TIMESTAMP WITH TIME ZONE` if H2 and the target DB both support it.
  - Add `CREATE INDEX idx_links_expires_at ON links(expires_at)`.
- **Migration approach:**
  - If Flyway or Liquibase is present, add a new versioned migration such as `V2__add_link_expiry.sql`. Don't edit applied migrations.
  - If the schema comes from `schema.sql` or `ddl-auto`, the module needs proper migrations first. Otherwise AC 12 (existing rows kept) can't be met reliably. That would be a dependency change and needs approval.
- **No backfill.** Existing rows stay `NULL`, which means they never expire.
- This is a schema change and **needs human approval**.

## Data flows
- **Create**
  - Before: request → validate URL → generate code (with collision handling) → save link → `201 {code, shortUrl}`.
  - After: request → validate URL and `ttlSeconds` → generate code → `expiresAt = now(clock) + ttl` → save the link with `expires_at` → `201 {code, shortUrl, expiresAt}`.
- **Redirect**
  - Before: request → rate limiter → look up code → 404 if missing → add a click → `302 Location`.
  - After: request → rate limiter (429 first) → look up code → 404 if missing → if `expires_at != null && now >= expires_at`, return 410 with no click → otherwise add a click → `302`.
  - **Caching:** if redirects are cached (in-app cache or `Cache-Control` on 302), entries must not outlive `expiresAt`.
- **Cleanup (new)**
  - The scheduler fires every `cleanupInterval`.
  - It computes `cutoff = now - cleanupGrace`.
  - It runs `DELETE FROM links WHERE expires_at < cutoff`, in batches.
  - It logs or records the number of deleted rows.
  - After deletion, those codes return 404 and collision handling may hand them out again.
- **Click data:** if clicks live in a separate table with a foreign key to links, cleanup has to delete those rows first or use `ON DELETE CASCADE`. Please verify where clicks are stored.

## Risks
- **High: existing clients see a behaviour change.** New links now expire after 30 days by default, where before they never did. *Mitigation:* call it out in release notes and the OpenAPI description, and have the API change approved explicitly.
- **High: cleanup deletes the wrong rows or fails because of foreign keys.** *Mitigation:*
  - Use a strict `expires_at < cutoff` predicate; `NULL` rows never match.
  - Handle child click rows explicitly.
  - Test the 8-day, 6-day and not-expired cases plus a NULL row.
  - Delete in batches.
- **Medium: a reused code sends old shared links somewhere new.** After the grace period, a deleted code can be given to a new link. *Mitigation:* document it as an accepted risk. Optionally keep a tombstone table later (out of scope for now).
- **Medium: clock and timezone mismatches.** Mixing `LocalDateTime` with the JVM default timezone can shift expiry. *Mitigation:* use `Instant` and a UTC `Clock` bean, and test the `now == expiresAt` boundary with a fixed clock.
- **Medium: the migration fails or loses data on an existing DB.** *Mitigation:*
  - Use an additive, nullable column and a separate index.
  - Add a test that applies the migration to seeded data.
  - Confirm how the schema is managed today.
- **Medium: clicks counted on expired links.** If the click increment happens in a lookup query or repository method before the expiry check, expired hits get counted. *Mitigation:* put the expiry check first and assert the click count is unchanged in AC 6 tests.
- **Low: cleanup runs on several instances at once.** *Mitigation:* single instance is assumed (out of scope). The deletes are idempotent, so the only cost is wasted work. Add ShedLock later if needed.
- **Low: 410 vs 404 lets callers tell expired codes from unknown ones.** *Mitigation:* accepted per the requirements, since the 410 window is limited by the grace period.
- **Low: misconfigured properties** (zero or negative durations). *Mitigation:* validate `LinkProperties` with `@Validated` and fail at startup.
- **Low: the scheduler is never enabled.** *Mitigation:* add `@EnableScheduling`, and test the job method directly rather than relying on timing.