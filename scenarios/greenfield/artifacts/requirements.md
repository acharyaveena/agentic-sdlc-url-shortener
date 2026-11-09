## Problem statement
The `url-shortener` module is empty and needs a complete Spring Boot service (Java 21, H2). The service lets users create a short code for a long URL and redirects anyone who visits that short code to the original URL. Input URLs must be validated, short-code collisions must be handled with a bounded number of retries, each redirect must be counted, and link creation must be rate-limited per client. The work also includes an OpenAPI spec, a database schema, and unit and integration tests.

## Ambiguities
- **Maximum URL length is not given.** This value controls validation and the database column size.
- **"Points back to this service" is not defined.** It could mean only the configured public host, or also `localhost`, loopback and private IPs, and alias hostnames. This affects security against redirect loops.
- **Short code format and length are not given.** The format, alphabet, and length determine how often collisions happen and whether codes can be guessed.
- **Custom aliases are not mentioned.** If clients can choose their own codes, the API, validation rules and collision behaviour change.
- **Duplicate long URLs are not covered.** The service could return the existing code or create a new one. This affects the schema (a unique index or not) and click statistics.
- **Collision retry count and the failure response are not given.** Clients need a defined error after all retries fail.
- **The redirect status code is not given.** The choice between 301/308 and 302/307 matters because browsers cache permanent redirects, so later clicks would not be counted.
- **"Per client" for rate limiting is not defined.** It could mean IP address, API key, or `X-Forwarded-For`. The limit values, time window and response are also not given.
- **Click counting consistency is not given.** Atomic increments, async batching, and per-click event rows are all possible. This affects accuracy under concurrent requests and the schema.
- **The stats endpoint content is not defined.** It could return only the total count, or also the creation time and last-access time.
- **Stats access control is not given.** Anyone who knows a code could see its stats. This affects privacy.
- **URL normalization is not specified.** Examples are case, trailing slashes and IDN hosts. This affects duplicate detection and length checks.
- **Persistence mode is not given.** H2 can run in-memory or file-based, which decides whether data survives a restart.
- **The schema management tool is not given.** Options are `schema.sql`, Flyway or Liquibase. Flyway or Liquibase would be a new dependency and needs allowlist approval.

## Assumptions
- **Maximum URL length:** 2048 characters, measured after trimming whitespace. The column is `VARCHAR(2048)`.
- **Self-reference rule:** A URL is rejected if its host matches the configured `app.base-url` host or any entry in the configurable `app.blocked-hosts` list. By default that list contains `localhost`, `127.0.0.1` and `::1`. Hosts are compared case-insensitively and ports are ignored. DNS is not resolved.
- **Validation rules:**
  - Only the `http` and `https` schemes are accepted.
  - The URL must have a host.
  - URLs containing user-info are rejected.
  - URLs are stored as submitted after trimming. No other normalization is done.
- **Short codes:** 7 characters drawn randomly from Base62 using `SecureRandom`.
- **No custom aliases.**
- **Duplicate long URLs:** Every create request makes a new short code.
- **Collision handling:**
  - A unique constraint on `short_code` detects collisions.
  - The service retries up to 5 times. If every attempt collides, it returns HTTP 503 with a problem-details body.
  - The code generator is behind an interface so tests can force collisions.
- **Redirect:** `GET /{code}` returns `302 Found` with a `Location` header. An unknown code returns 404.
- **Click counting:** Each redirect runs one atomic `UPDATE ... SET click_count = click_count + 1` in the same request. No per-click event table is created.
- **Stats endpoint:** `GET /api/v1/links/{code}/stats` returns `code`, `longUrl`, `clickCount` and `createdAt`. It is public and has no authentication.
- **Create endpoint:**
  - `POST /api/v1/links` takes the body `{"url": "..."}`.
  - It returns `201 Created` with `code`, `shortUrl`, `longUrl` and `createdAt`, plus a `Location` header.
- **Rate limiting:**
  - Clients are identified by `request.getRemoteAddr()`. `X-Forwarded-For` is not trusted.
  - The limit is an in-memory fixed or sliding window, configurable, with a default of 10 creations per minute per client.
  - Over the limit, the service returns HTTP 429 with a `Retry-After` header.
  - The limit applies only to `POST /api/v1/links`.
  - No new dependency is added for this, so Bucket4j is not used.
- **Errors:** All errors use RFC 7807 problem-details via Spring `ProblemDetail`. Validation failures return 400.
- **Persistence:**
  - H2 runs file-based in the default profile and in-memory in tests.
  - The schema lives in `src/main/resources/schema.sql`. Flyway is not used, so no new dependency is needed.
  - Table `links` has these columns: `id` BIGINT identity primary key, `short_code` VARCHAR(16) UNIQUE NOT NULL, `long_url` VARCHAR(2048) NOT NULL, `click_count` BIGINT NOT NULL DEFAULT 0, `created_at` TIMESTAMP NOT NULL.
- **OpenAPI:** A static spec is committed at `url-shortener/src/main/resources/openapi.yaml` and written by hand. Adding springdoc would be a new dependency that needs approval, so it is not used unless approved.
- **Tests:** Unit tests use JUnit 5 with Mockito. Integration tests use `@SpringBootTest` with MockMvc against in-memory H2. All of these are already in `spring-boot-starter-test`.

## Acceptance criteria
1. Given a valid `https://example.com/page` URL, when a client POSTs it to `/api/v1/links`, then the response is 201. The body contains a 7-character Base62 `code` and a `shortUrl` equal to `{app.base-url}/{code}`, and the link is stored with `click_count = 0`.
2. Given a URL with scheme `ftp`, `javascript`, `file` or `mailto`, or with no scheme, when it is POSTed, then the response is 400 problem-details and nothing is stored.
3. Given a URL longer than 2048 characters, when it is POSTed, then the response is 400. Given a URL of exactly 2048 characters, then the response is 201.
4. Given an empty, blank or malformed URL, or a missing `url` field, when it is POSTed, then the response is 400.
5. Given a URL whose host equals the `app.base-url` host (any case, any port) or a blocked host such as `localhost` or `127.0.0.1`, when it is POSTed, then the response is 400 with a message saying self-referencing links are not allowed.
6. Given a code generator that returns an existing code twice and then a new code, when a link is created, then creation succeeds with the third code after 3 attempts.
7. Given a code generator that always returns an existing code, when a link is created, then it fails after exactly 5 attempts with HTTP 503 and no new row is stored.
8. Given an existing code, when a client sends `GET /{code}`, then the response is 302 with `Location` equal to the stored long URL, and that link's `click_count` goes up by exactly 1.
9. Given an unknown code, when a client sends `GET /{code}`, then the response is 404 and no click count changes.
10. Given 50 concurrent redirects to the same code, when they all finish, then `click_count` equals 50.
11. Given an existing code that has been redirected N times, when a client sends `GET /api/v1/links/{code}/stats`, then the response is 200 with `clickCount = N`, `longUrl` and `createdAt`. Given an unknown code, then the response is 404.
12. Given a creation limit of 10 per minute, when one client sends 11 creation requests within a minute, then the 11th returns 429 with a `Retry-After` header. A request from a different client address still succeeds.
13. Given a rate-limited client, when the window has passed, then that client can create links again.
14. Given that a client has hit the creation limit, when it calls the redirect or stats endpoints, then those requests are not rate-limited.
15. Given the module source, when it is inspected, then `openapi.yaml` describes every endpoint, request and response schema, and error status (400, 404, 429, 503), and `schema.sql` defines the `links` table with a unique constraint on `short_code`.
16. Given the module, when `./mvnw verify` runs, then all unit and integration tests pass with no new dependencies beyond Spring Boot web, data-jdbc or JPA, validation, H2 and starter-test.

## Out of scope
- Custom aliases, link editing and link deletion
- Link expiry (covered by the brownfield scenario)
- Authentication, user accounts, and ownership of links or stats
- Detailed analytics such as per-click timestamps, referrers, geo data and user agents
- Distributed or persistent rate limiting (state is in memory on a single instance), and trusting proxy headers
- DNS resolution or checking private IP ranges to block self-reference (SSRF hardening)
- URL normalization and deduplication
- Malware or phishing URL screening
- A UI front end
- Production database and migration tooling (Flyway or Liquibase), deployment and containerization