## Problem statement
The `url-shortener` module has no service yet, so we need to build one with Java 21, Spring Boot 3.3 and H2. Clients must be able to create a short code for a long URL and be redirected when they visit that code. URLs must be validated, short-code collisions must be handled safely, clicks must be counted and exposed through a stats endpoint, and link creation must be rate-limited per client. The delivery also includes an OpenAPI spec, a database schema, and unit and integration tests, so the service can be governed and extended later (for example, link expiry).

## Ambiguities
- **Maximum URL length is not given.** It sets the size of the database column and decides which requests are rejected.
- **"Points back to this service" is not defined.** It could mean only the configured public host, or also localhost, loopback IPs, other host aliases, and redirect chains through other shorteners. This decides how strong the open-redirect and loop protection is.
- **Short-code format is not given** (length, alphabet, random or sequential). It sets the collision probability, whether codes can be guessed, and the number of retries needed.
- **Retry limit and the response when it is exhausted are not given.** Clients need a stable, documented error.
- **"Per client" for rate limiting is not defined** (IP address, API key or user). There is no authentication, and proxies affect how a client is identified.
- **Rate limit values and algorithm are not given** (requests per window, fixed or sliding window, token bucket). This affects behaviour under bursts and what the tests can assert.
- **The rate-limit state store is not specified.** In-memory state works for a single instance but not for several.
- **Redirect status code is not given** (301, 302, 307 or 308). Browsers cache 301/308 responses, so later visits never reach the server and are not counted.
- **Click-counting consistency is not given** (exact under concurrency or best-effort, synchronous or asynchronous). This affects correctness under parallel redirects.
- **Contents of the stats endpoint are not given** (total count only, or also creation time and last access). This sets the API contract.
- **Duplicate long URLs:** the request does not say whether the same URL returns the existing code or a new one. This changes behaviour and storage use.
- **Custom aliases (user-chosen codes)** are not mentioned. This decides whether a collision can be a user error.
- **Base URL in responses:** where the public host used to build the short URL comes from is not stated. It also feeds the self-reference check.
- **Should the OpenAPI spec be hand-written, generated, or both?** Generating it at runtime adds a dependency, which needs approval.
- **Schema management** (plain `schema.sql`, Flyway or Liquibase) is not specified. Flyway or Liquibase would add a dependency, and later schema changes need approval.
- **H2 mode** (in-memory or file-based) is not stated. It decides whether data survives a restart.
- **Coverage target for tests** is not stated, so there is no measurable bar.

## Assumptions
- Maximum URL length is **2048 characters**, measured after trimming. Longer URLs get 400.
- **Self-reference rule:** reject a URL whose host, compared case-insensitively, matches the configured `app.base-url` host, `localhost`, `127.0.0.0/8`, `::1` or `0.0.0.0`. Hosts are not resolved through DNS and redirect chains are not followed.
- **Accepted URLs:** the scheme must be `http` or `https` (case-insensitive), the host must be present, and user-info (`user:pass@`) is not allowed. Anything else gets 400.
- **Short codes** are 7 random characters from the Base62 alphabet `[0-9A-Za-z]`, generated with `SecureRandom`. The generator sits behind an interface so tests can force collisions.
- **Collisions:** a unique constraint on `code` is the source of truth. On a constraint violation the service generates a new code, up to **5 attempts**. If all attempts fail it returns **503** with error code `CODE_GENERATION_EXHAUSTED`.
- **Client identity:** clients are identified by remote IP. `X-Forwarded-For` is ignored unless `app.rate-limit.trust-forwarded-for=true`, in which case the first entry is used.
- **Rate limit:** an in-memory token bucket allows **10 creations per minute per client** (configurable). Only `POST /api/links` is limited. Over the limit returns **429** with a `Retry-After` header. Redirects and stats are not limited.
- Rate limiting is implemented by hand with no external library, and runs as a single instance.
- **Redirects use 302 Found**, so browsers do not cache them and every visit is counted.
- **Click counting:** each redirect runs one atomic `UPDATE links SET click_count = click_count + 1 WHERE code = ?` in the same request. The count must be exact under concurrent redirects.
- **Stats:** `GET /api/links/{code}/stats` returns `code`, `longUrl`, `createdAt` and `clickCount`.
- **Duplicate URLs:** every creation request gets a new code, so long URLs are not de-duplicated.
- There are no custom aliases.
- **Base URL:** short URLs are built from the configurable `app.base-url` property (default `http://localhost:8080`).
- **API:**
  - `POST /api/links` with body `{"url": "..."}` returns **201** with `code`, `shortUrl`, `longUrl` and `createdAt`, plus a `Location` header.
  - `GET /{code}` returns 302, or 404 for an unknown code.
  - Errors use a consistent JSON body: `{"error": "...", "message": "..."}`.
- **OpenAPI:** a hand-written OpenAPI 3.0 file at `url-shortener/src/main/resources/openapi.yaml`. No new dependency is added; adding springdoc would need separate approval.
- **Database schema:** a `schema.sql` file with table `links`:
  - `id` BIGINT identity primary key
  - `code` VARCHAR(16) unique, not null
  - `long_url` VARCHAR(2048), not null
  - `created_at` TIMESTAMP, not null
  - `click_count` BIGINT, not null, default 0

  There is no Flyway. This schema needs human approval because it is a schema/API change.
- **H2** runs in-memory by default, with a file-based option through configuration.
- **Dependencies** are limited to existing Spring Boot starters: web, data-jdbc or data-jpa, validation, test, and the H2 driver.
- **Tests:**
  - Unit tests cover the validator, code generator, collision retry and rate limiter.
  - Integration tests use `@SpringBootTest` with MockMvc and H2 and cover the full API.
  - Target: at least 80% line coverage of the service and validation code.
- **Unknown codes:** a redirect to an unknown code returns 404. Code paths are only matched if they fit `^[0-9A-Za-z]{1,16}$`, so `/api/**` and the actuator endpoints never collide with a code.

## Acceptance criteria
1. Given a valid `https://example.com/page` URL, when a client sends `POST /api/links`, then the response is 201. The body has a 7-character Base62 `code`, `shortUrl` = `{app.base-url}/{code}`, the original `longUrl` and `createdAt`, and a `Location` header is set.
2. Given an existing code, when a client sends `GET /{code}`, then the response is 302 with `Location` equal to the stored long URL.
3. Given an unknown code, when a client sends `GET /{code}`, then the response is 404 with a JSON error body, and no click count is changed.
4. Given a URL with scheme `ftp`, `javascript`, `file` or `mailto`, or no scheme at all, when it is submitted, then the response is 400 with `error` = `INVALID_URL`.
5. Given a URL longer than 2048 characters, when it is submitted, then the response is 400 with `error` = `URL_TOO_LONG`.
6. Given an empty, blank or missing `url` field, or a body that is not JSON, when it is submitted, then the response is 400.
7. Given a URL whose host matches the `app.base-url` host, `localhost`, `127.0.0.1` or `[::1]`, when it is submitted, then the response is 400 with `error` = `SELF_REFERENCE`.
8. Given a code generator stubbed to return an existing code twice and then a new code, when a link is created, then creation succeeds with the new code after exactly 3 generation attempts.
9. Given a code generator stubbed to always return an existing code, when a link is created, then generation stops after 5 attempts, the response is 503 with `error` = `CODE_GENERATION_EXHAUSTED`, and no row is inserted.
10. Given a link with `clickCount` 0, when `GET /{code}` is called 3 times, then `GET /api/links/{code}/stats` returns `clickCount` = 3.
11. Given a link, when 50 redirects run concurrently, then `clickCount` is exactly 50.
12. Given an unknown code, when `GET /api/links/{code}/stats` is called, then the response is 404.
13. Given the default limit of 10 per minute, when one client IP sends 11 `POST /api/links` requests within a minute, then the first 10 get 201 and the 11th gets 429 with a positive `Retry-After` header.
14. Given client A has reached its limit, when client B (a different IP) sends `POST /api/links`, then client B gets 201.
15. Given a client at its creation limit, when it calls `GET /{code}` or the stats endpoint, then it does not get 429.
16. Given the `links` table, when the same `code` is inserted twice directly, then the database rejects the second insert with a unique-constraint violation.
17. Given the application starts, when the schema is applied, then table `links` exists with the columns, types and constraints listed under Assumptions.
18. Given `openapi.yaml`, when it is parsed by an OpenAPI 3 validator in a test, then it is valid. It documents `POST /api/links`, `GET /{code}` and `GET /api/links/{code}/stats`, with every status code listed above (201, 302, 400, 404, 429, 503).
19. Given the module, when `./mvnw verify` is run, then all unit and integration tests pass and line coverage of the service and validation packages is at least 80%.
20. Given the build, when dependencies are listed, then only approved Spring Boot starters and H2 are present. The single exception is a test-only OpenAPI parser, which must be called out for approval.

## Out of scope
- Link expiry or TTL (planned as the later brownfield change).
- Authentication, API keys, user accounts and link ownership.
- Custom aliases or vanity codes.
- Deleting or editing links.
- Detailed analytics: referrer, geography, user agent, time series and unique visitors.
- Distributed or shared rate-limit storage (Redis etc.) and multi-instance deployment.
- DNS-based self-reference or SSRF checks, following redirect chains, and malware or phishing URL screening.
- Production databases other than H2, and migration tooling (Flyway or Liquibase).
- UI or frontend, QR codes, and caching layers.
- Generating the OpenAPI spec at runtime with springdoc or Swagger UI.
- Deployment, containerisation, TLS and observability beyond Spring Boot defaults.