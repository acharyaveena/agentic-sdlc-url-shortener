## Problem statement
The `url-shortener` module has no code yet. We need a Java 21 / Spring Boot 3.3 service backed by H2 that turns a long URL into a short code and redirects visitors who open that code. The service must reject unsafe or invalid URLs, handle short-code collisions with a limited number of retries, count clicks per link and report them through a stats endpoint, and rate-limit link creation per client. The work also includes an OpenAPI spec, a database schema, and unit and integration tests.

## Ambiguities
- **Maximum URL length is not given.** It sets the column size and the validation limit.
- **"Points back to this service" is not defined.** It could mean only the configured public host, or also `localhost`, loopback IPs, aliases and other ports. If the rule is too narrow, someone can create redirect loops or chained short links.
- **Short-code format is not given (length, alphabet, random or sequential).** This affects how often collisions happen, how easy codes are to guess, and how the code column is defined.
- **The retry limit for collisions and the response when retries run out are not given.** Clients need a predictable error.
- **How a "client" is identified for rate limiting is not given** (IP address, API key or header). Behind a proxy, the remote address may be the proxy's address.
- **The rate-limit numbers and algorithm are not given.** Tests need fixed numbers, and clients need to know what response they get when blocked.
- **The redirect status code is not given (301, 302, 307 or 308).** Browsers cache a 301, so repeat visits may never reach the service and clicks would be undercounted.
- **What counts as a click is not defined.** Bots, `HEAD` requests and repeat visits could all be counted or ignored. This decides whether the counter is exact and how it handles concurrent requests.
- **The contents of the stats endpoint are not given.** It could return only the count, or also the original URL, creation time and last access time.
- **Behaviour for duplicate long URLs is not given.** The service could return the existing code or create a new one, and this changes the data model.
- **Whether users can choose their own codes is not stated.** Custom codes need extra validation and conflict handling.
- **Whether creation needs authentication is not stated.** The project rules exclude Spring Security.
- **URL normalization is not defined** (letter case, trailing slash, punycode, fragments). It affects the length check and the self-link check.
- **How the schema is managed is not stated** (hand-written `schema.sql` or Flyway/Liquibase). A migration tool would be a new dependency.
- **Whether the OpenAPI spec is a static file or generated from code is not stated.** Generating it with springdoc would add a dependency.
- **Required test coverage is not stated.** The release gate needs a number to check against.

## Assumptions
- The maximum URL length is **2048 characters**, measured after trimming.
- A URL is a self-link if its host, ignoring case, matches the configured `app.base-url` host **or** is `localhost`, `127.0.0.0/8`, `::1` or `0.0.0.0`, on any port. The hosts are configurable through `app.blocked-hosts`.
- The URL must parse as an absolute URI with scheme `http` or `https` and a non-empty host. Any other scheme, including `javascript`, `data`, `ftp` and `file`, is rejected. Embedded user info (`user@host`) is rejected.
- Short codes are **7 characters of Base62** produced by `SecureRandom`. Uniqueness is enforced by a database `UNIQUE` constraint.
- On a collision (`DataIntegrityViolationException`), the service generates a new code up to **5 attempts** in total. If all fail, it returns **503** with a problem+json body (`code-generation-exhausted`).
- Each creation request gets a new code, even if the same long URL was shortened before.
- Custom codes are not supported.
- A client is identified by **`request.getRemoteAddr()`**. `X-Forwarded-For` is ignored unless `app.rate-limit.trust-forwarded-for=true`.
- Rate limiting uses an in-memory **token bucket** of **10 creations per minute per client**, configurable. A blocked request gets **429** with a `Retry-After` header. Only `POST /api/v1/links` is limited.
- The redirect uses **302 Found**, so every visit reaches the service and is counted.
- A click is every `GET /{code}` that results in a redirect. `HEAD` requests, 404s and bots are not filtered out.
- The counter is updated in the database with an atomic `UPDATE ... SET click_count = click_count + 1` so no clicks are lost under concurrent requests.
- **API:**
  - `POST /api/v1/links` with body `{ "url": "..." }` returns 201 with `{ code, shortUrl, targetUrl, createdAt }` and a `Location` header.
  - `GET /{code}` returns 302, or 404 if the code is unknown.
  - `GET /api/v1/links/{code}/stats` returns 200 with `{ code, targetUrl, clickCount, createdAt, lastAccessedAt }`, or 404.
- Errors are returned as RFC 7807 `application/problem+json`. Validation failures return 400.
- **Schema:** a hand-written `schema.sql` defines table `short_link` with these columns:
  - `id` BIGINT IDENTITY PK
  - `code` VARCHAR(16) UNIQUE NOT NULL
  - `target_url` VARCHAR(2048) NOT NULL
  - `click_count` BIGINT NOT NULL DEFAULT 0
  - `created_at` TIMESTAMP NOT NULL
  - `last_accessed_at` TIMESTAMP NULL

  Spring Data JPA is used for data access and `ddl-auto=validate`. No Flyway is added.
- The OpenAPI 3.0 spec is a static file at `url-shortener/src/main/resources/static/openapi.yaml` and is tested against the controllers. No springdoc dependency is added.
- No authentication on any endpoint.
- Allowed dependencies are `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `com.h2database:h2` and `spring-boot-starter-test`. The rate limiter is written in-house, with no Bucket4j.
- Coverage target is **≥ 80% line coverage** on the service and controller packages, measured with JaCoCo as a build plugin.
- The URL is not normalized beyond trimming and lower-casing the scheme and host for checks. It is stored exactly as submitted after trimming.

## Acceptance criteria
1. Given a valid URL `https://example.com/a?b=1`, when a client sends it to `POST /api/v1/links`, then the response is 201. The body has a 7-character Base62 `code` and `targetUrl` equal to the input, and the `Location` header points to the short URL.
2. Given an existing code, when a client sends `GET /{code}`, then the response is 302 with `Location` equal to the stored target URL.
3. Given an unknown code, when a client sends `GET /{code}` or `GET /api/v1/links/{code}/stats`, then the response is 404 with a problem+json body.
4. Given a link that has been redirected 3 times, when a client calls the stats endpoint, then `clickCount` is 3 and `lastAccessedAt` is not null.
5. Given 50 concurrent `GET /{code}` requests, when they all finish, then `clickCount` is exactly 50.
6. Given a URL with scheme `ftp`, `javascript`, `file` or `data`, or no scheme at all, when a client submits it, then the response is 400 and no row is stored.
7. Given a URL longer than 2048 characters, when a client submits it, then the response is 400. A URL of exactly 2048 characters is accepted.
8. Given a URL whose host is the configured base-URL host, `localhost`, `127.0.0.1` or `[::1]`, on any port, when a client submits it, then the response is 400 with a problem type that says it is a self-referencing link.
9. Given a blank body, a missing `url` field, malformed JSON or a URL containing user info, when a client submits it, then the response is 400.
10. Given a code generator stubbed to return a taken code twice and then a free one, when a link is created, then creation succeeds on the 3rd attempt and the stored code is the free one.
11. Given a code generator stubbed to always return a taken code, when a link is created, then the service tries exactly 5 times and returns 503 `code-generation-exhausted`.
12. Given one client IP, when it sends 11 creation requests within one minute, then the first 10 return 201 and the 11th returns 429 with a `Retry-After` header.
13. Given that one client has used its limit, when a different client IP sends a creation request, then that request returns 201.
14. Given that one client has used its creation limit, when that client calls `GET /{code}` or the stats endpoint, then those requests are not rate-limited.
15. Given the application starts against an empty H2 database, when `schema.sql` runs, then table `short_link` exists with a unique constraint on `code`, and JPA `validate` passes.
16. Given `openapi.yaml`, when it is parsed by a test, then it is valid OpenAPI 3. It documents all three operations and the 201, 302, 400, 404, 429 and 503 responses, and each documented path matches a controller mapping.
17. Given `./mvnw verify` runs, when the build finishes, then all unit tests (validator, code generator, rate limiter, service with retry logic) and `@SpringBootTest` integration tests pass, and JaCoCo line coverage of the service and web packages is at least 80%.
18. Given the full change set, when the policy scan runs, then all changed files are inside `url-shortener/`, it finds no secrets, and every dependency is on the allowlist.

## Out of scope
- User accounts, authentication, authorization, and API keys.
- Custom or vanity codes, link editing, link deletion, and link expiry (expiry is a later brownfield scenario).
- Detailed analytics such as referrer, geography, user agent, unique visitors, time series and bot filtering.
- A rate limiter that works across several instances (Redis or similar). The limiter keeps state in one process only.
- Checking target URLs for malware or phishing, and following chained shorteners.
- Production database, migration tooling, a cache layer, and running multiple instances.
- Full URL canonicalization, and rejecting duplicate URLs or reusing an existing code for them.
- UI or front end; generated client SDKs; generating the OpenAPI spec at runtime.
- Deployment, containerization, TLS, and observability beyond Spring Boot defaults.