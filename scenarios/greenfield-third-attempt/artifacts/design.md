## Design overview
This is a single Spring Boot 3.3 application in `url-shortener/`. It uses Spring MVC, Spring Data JDBC, Bean Validation, H2 and a hand-written `schema.sql` and `openapi.yaml`. The base package is `com.example.urlshortener`; adjust it to the module's groupId if one exists. The code and tests stages work in parallel against the contracts below. The build+test join checks that they fit together.

**Components and contracts**

| Class (package) | Responsibility / contract |
|---|---|
| `AppProperties` (`config`) | Bound with `@ConfigurationProperties("app")`. Settings: `baseUrl` (default `http://localhost:8080`), `blockedHosts` (default `localhost`, `127.0.0.1`, `::1`), `maxUrlLength` = 2048, `codeLength` = 7, `maxCodeAttempts` = 5, `rateLimit.requests` = 10, `rateLimit.window` = `PT1M`. |
| `ClockConfig` (`config`) | Provides a `Clock` bean (UTC). Tests replace it with a mutable clock. |
| `Link` (`link`) | Record: `id`, `shortCode`, `longUrl`, `clickCount`, `createdAt` (`Instant`). Mapped to the `links` table. |
| `LinkRepository` (`link`) | `CrudRepository<Link, Long>` plus `Optional<Link> findByShortCode(String)` and `@Modifying @Query int incrementClickCount(String code)`. The query is `UPDATE links SET click_count = click_count + 1 WHERE short_code = :code`. |
| `ShortCodeGenerator` (`code`) | Interface: `String next()`. |
| `RandomBase62CodeGenerator` (`code`) | Uses `SecureRandom` to return `codeLength` characters from `[0-9A-Za-z]`. |
| `UrlValidator` (`validation`) | `String validate(String raw)` returns the trimmed URL or throws `InvalidUrlException`. Checks run in this order: (1) blank → error; (2) length > `maxUrlLength` → error; (3) `new URI(...)` fails → malformed; (4) scheme is not `http`/`https` (case-insensitive) → error; (5) no host → error; (6) has user-info → error; (7) host (case-insensitive, brackets stripped, port ignored) equals the `baseUrl` host or is in `blockedHosts` → error "Self-referencing links are not allowed". |
| `LinkService` (`link`) | Three operations, described in the next three rows. |
| `LinkService.create(url)` | Validates the URL, then tries up to `maxCodeAttempts` times: generate a code and `save`. On `DuplicateKeyException`/`DbActionExecutionException` caused by a duplicate key, it tries again. After the last failed attempt it throws `CodeGenerationExhaustedException`. Each insert runs in its own transaction, so `create` itself is **not** `@Transactional`. |
| `LinkService.resolve(code)` | `@Transactional`. Runs `incrementClickCount`. If 0 rows change it throws `LinkNotFoundException`. Otherwise it returns the `longUrl`. |
| `LinkService.stats(code)` | Returns the link or throws `LinkNotFoundException`. |
| `LinkController` (`web`) | `POST /api/v1/links` returns 201 with a `Location` header pointing to the short URL. `GET /api/v1/links/{code}/stats` returns 200. |
| `RedirectController` (`web`) | `GET /{code:[0-9A-Za-z]{1,16}}` returns 302 with a `Location` header. |
| DTOs (`web`) | `CreateLinkRequest(@NotBlank String url)`, `LinkResponse(code, shortUrl, longUrl, createdAt)`, `LinkStatsResponse(code, longUrl, clickCount, createdAt)`. |
| `FixedWindowRateLimiter` (`ratelimit`) | `Decision tryAcquire(String clientKey)`. `Decision` holds `allowed` and `retryAfterSeconds`. State is a `ConcurrentHashMap<String, Window>` updated with `compute`, so it is atomic per key. Expired windows are cleaned up opportunistically. Uses the injected `Clock`. |
| `RateLimitInterceptor` (`ratelimit`) | Registered for `/api/v1/links`. Only acts on `POST`. The client key is `request.getRemoteAddr()`. When the limit is hit it throws `RateLimitExceededException(retryAfterSeconds)`. |
| `ApiExceptionHandler` (`web`) | `@RestControllerAdvice` that extends `ResponseEntityExceptionHandler` and returns `ProblemDetail` bodies. Mappings: `InvalidUrlException` → 400; `LinkNotFoundException` → 404; `RateLimitExceededException` → 429 with a `Retry-After` header; `CodeGenerationExhaustedException` → 503. A missing field or malformed JSON → 400 (inherited handlers). |

**Key decisions and trade-offs**
- **Spring Data JDBC instead of JPA.** It is simpler, has no dirty-checking, and the atomic `UPDATE` is explicit. The cost is no lazy loading or entity graphs, which this service does not need.
- **Collisions are detected by the DB unique constraint and retried.** This avoids a check-then-insert race. The cost is that a failed insert is spent per collision; at 62⁷ ≈ 3.5×10¹² codes, collisions are rare.
- **Increment first, then return the URL, in one transaction.** One statement both checks that the code exists and counts the click atomically, which meets AC9 and AC10. The cost is a row lock per redirect, which is fine for a single H2 instance.
- **302 instead of 301/308.** Browsers do not cache 302, so every click reaches the service and gets counted. The cost is slightly more load.
- **In-memory fixed-window rate limiter and an MVC interceptor, with no Bucket4j.** No new dependency is needed. The cost is that the limit is per instance, a client can burst up to 2× at a window boundary, and state is lost on restart. These are accepted as out of scope.
- **Remote address is the client key.** It cannot be spoofed through headers. The cost is that clients behind one NAT or proxy share a limit.
- **Hand-written `openapi.yaml` and `schema.sql`.** No springdoc or Flyway dependency is needed. The cost is that the spec can drift from the code. A contract test (T7) checks that the spec lists every endpoint.
- **H2 storage.** The default profile uses a file (`jdbc:h2:file:./data/urlshortener`) with `spring.sql.init.mode=always` and idempotent DDL. Tests use `jdbc:h2:mem` and a high rate limit (`app.rate-limit.requests=1000`), except in the rate-limit tests. Add `data/` to `.gitignore`.
- **Redirect path is limited to `[0-9A-Za-z]{1,16}`.** This prevents clashes with `/api/**` and paths such as `/favicon.ico`. A malformed code gets 404 without touching the DB.

## Task plan
| id | task | depends on | stage |
|---|---|---|---|
| C1 | Module scaffold: `pom.xml` (Spring Boot 3.3 parent, Java 21, starter-web, starter-data-jdbc, starter-validation, h2 runtime, starter-test), `UrlShortenerApplication`, `application.yml` (file H2, `sql.init.mode=always`, `app.*` defaults), `ClockConfig`, `AppProperties`. Covers AC16. | – | code |
| C2 | `schema.sql` with the `links` table and unique constraint on `short_code`. Covers AC1 and AC15. | C1 | code |
| C3 | `Link` record and `LinkRepository`, including the atomic `incrementClickCount`. Covers AC8 and AC10. | C2 | code |
| C4 | `ShortCodeGenerator` interface and `RandomBase62CodeGenerator` (7 characters, SecureRandom). Covers AC1. | C1 | code |
| C5 | `UrlValidator` and `InvalidUrlException`, with scheme, length, malformed, host, user-info and self-reference rules. Covers AC2–AC5. | C1 | code |
| C6 | `LinkService` with create-and-retry (max 5, then `CodeGenerationExhaustedException`), resolve (increment, 404 on unknown) and stats. Covers AC1, AC6–AC9 and AC11. | C3, C4, C5 | code |
| C7 | DTOs, `LinkController`, `RedirectController` and `ApiExceptionHandler` (ProblemDetail mappings for 400, 404, 429 and 503). Covers AC1–AC5, AC8, AC9 and AC11. | C6 | code |
| C8 | `FixedWindowRateLimiter`, `RateLimitInterceptor` and its `WebMvcConfigurer` registration (POST `/api/v1/links` only), plus `RateLimitExceededException` with `Retry-After`. Covers AC12–AC14. | C1, C7 | code |
| C9 | `src/main/resources/openapi.yaml` (full spec, as in the next section). Covers AC15. | C7 | code |
| T1 | `RandomBase62CodeGeneratorTest`: length is 7, every character is in the Base62 alphabet, and 10,000 generated codes are distinct. Covers AC1. | C4 | tests |
| T2 | `UrlValidatorTest`, parameterized. `ftp:`, `javascript:`, `file:`, `mailto:` and no-scheme URLs are rejected. Blank, empty and malformed URLs are rejected. A 2049-character URL is rejected and a 2048-character URL is accepted. User-info is rejected. `HTTP://SHORT.EXAMPLE:9999/x` with `baseUrl` `https://short.example` is rejected with the self-reference message. `localhost`, `127.0.0.1` and `[::1]` are rejected. Valid input is trimmed. Covers AC2–AC5. | C5 | tests |
| T3 | `LinkServiceTest` (Mockito). (a) The generator returns `dup`, `dup`, `new`; the first 2 saves throw `DuplicateKeyException`; the result has code `new` and `save` was called 3 times. (b) Every save throws a duplicate error; `CodeGenerationExhaustedException` is thrown and `save` was called 5 times. (c) When `incrementClickCount` returns 0, `resolve` throws `LinkNotFoundException`. Covers AC6, AC7 and AC9. | C6 | tests |
| T4 | `FixedWindowRateLimiterTest` (mutable clock). 10 requests are allowed and the 11th is denied with `retryAfterSeconds` > 0. A different key is still allowed. After the clock moves forward 60 s, the key is allowed again. 20 threads × 1 key allow exactly 10 requests. Covers AC12 and AC13. | C8 | tests |
| T5 | `LinkApiIT` (`@SpringBootTest` + MockMvc, in-memory H2). A valid create returns 201 with a code matching `^[0-9A-Za-z]{7}$`, `shortUrl` = `baseUrl/code`, a `Location` header, and a DB row with `click_count` = 0. Invalid inputs (bad schemes, missing `url`, malformed JSON, too-long URL, self-reference) return 400 `application/problem+json`, and the row count stays the same. A 2048-character URL returns 201. Redirect returns 302 with `Location`, and `click_count` increases by 1. An unknown code returns 404 on redirect and on stats, and no counts change. After N redirects, stats returns `clickCount` = N with `longUrl` and `createdAt`. Covers AC1–AC5, AC8, AC9 and AC11. | C7 | tests |
| T6 | `CollisionIT` (`@MockBean ShortCodeGenerator`, plus a pre-inserted `dup` row). `dup`, `dup`, `new` returns 201 with code `new`. Always `dup` returns 503 problem-details, and the row count stays the same. Covers AC6 and AC7. | C7 | tests |
| T7 | `ConcurrentRedirectIT` (`RANDOM_PORT`, `java.net.http.HttpClient` with `Redirect.NEVER`). Sends 50 concurrent GETs through an `ExecutorService`; all return 302 and the final `click_count` is 50. Covers AC10. | C7 | tests |
| T8 | `RateLimitIT` (`app.rate-limit.requests=10`, mutable `Clock` bean, `remoteAddr` set through a MockMvc `RequestPostProcessor`). From 10.0.0.1, 10 POSTs return 201 and the 11th returns 429 with a `Retry-After` header. A POST from 10.0.0.2 returns 201. Redirect and stats from 10.0.0.1 still return 302/200. After the clock moves forward 61 s, 10.0.0.1 gets 201. Covers AC12–AC14. | C8 | tests |
| T9 | `ContractFilesTest`. `openapi.yaml` (read as text or with SnakeYAML, which ships with Boot) contains paths `/api/v1/links`, `/api/v1/links/{code}/stats` and `/{code}`, and responses 400, 404, 429 and 503. `schema.sql` contains `links` and a unique constraint on `short_code`. Covers AC15. | C2, C9 | tests |
| T10 | `./mvnw verify` passes in the module, and the dependency tree contains only the allowlisted starters and H2. Covers AC16. | T1–T9 | tests |

The tests stage can write T1–T9 at the same time as the code stage, using the class names, signatures, property names and HTTP contracts defined in **Design overview**. The "depends on" column shows the execution order at the build+test join.

## API changes
```yaml
openapi: 3.0.3
info:
  title: URL Shortener API
  version: 1.0.0
servers:
  - url: http://localhost:8080
paths:
  /api/v1/links:
    post:
      summary: Create a short link
      operationId: createLink
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateLinkRequest'
      responses:
        '201':
          description: Link created
          headers:
            Location:
              description: The short URL
              schema: { type: string, format: uri }
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LinkResponse'
        '400':
          $ref: '#/components/responses/BadRequest'
        '429':
          description: Creation rate limit exceeded for this client
          headers:
            Retry-After:
              description: Seconds until the client may retry
              schema: { type: integer, minimum: 1 }
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/ProblemDetail'
        '503':
          description: Could not allocate a unique short code after the maximum number of attempts
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/ProblemDetail'
  /api/v1/links/{code}/stats:
    get:
      summary: Get click statistics for a short link
      operationId: getLinkStats
      parameters:
        - $ref: '#/components/parameters/Code'
      responses:
        '200':
          description: Link statistics
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LinkStats'
        '404':
          $ref: '#/components/responses/NotFound'
  /{code}:
    get:
      summary: Redirect to the long URL and count the click
      operationId: redirect
      parameters:
        - $ref: '#/components/parameters/Code'
      responses:
        '302':
          description: Redirect to the stored long URL
          headers:
            Location:
              description: The stored long URL
              schema: { type: string, format: uri }
        '404':
          $ref: '#/components/responses/NotFound'
components:
  parameters:
    Code:
      name: code
      in: path
      required: true
      schema:
        type: string
        pattern: '^[0-9A-Za-z]{1,16}$'
  schemas:
    CreateLinkRequest:
      type: object
      required: [url]
      properties:
        url:
          type: string
          maxLength: 2048
          description: Absolute http or https URL that does not point back to this service
          example: https://example.com/page
    LinkResponse:
      type: object
      required: [code, shortUrl, longUrl, createdAt]
      properties:
        code:      { type: string, pattern: '^[0-9A-Za-z]{7}$', example: aZ3k9Qp }
        shortUrl:  { type: string, format: uri, example: http://localhost:8080/aZ3k9Qp }
        longUrl:   { type: string, format: uri }
        createdAt: { type: string, format: date-time }
    LinkStats:
      type: object
      required: [code, longUrl, clickCount, createdAt]
      properties:
        code:       { type: string }
        longUrl:    { type: string, format: uri }
        clickCount: { type: integer, format: int64, minimum: 0 }
        createdAt:  { type: string, format: date-time }
    ProblemDetail:
      type: object
      description: RFC 7807 problem details
      properties:
        type:     { type: string, format: uri }
        title:    { type: string }
        status:   { type: integer }
        detail:   { type: string }
        instance: { type: string }
  responses:
    BadRequest:
      description: Invalid or missing URL (bad scheme, too long, malformed, user-info, or self-referencing)
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/ProblemDetail'
    NotFound:
      description: Unknown short code
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/ProblemDetail'
```

## Schema changes
```sql
-- url-shortener/src/main/resources/schema.sql (idempotent; runs on every startup)
CREATE TABLE IF NOT EXISTS links (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    short_code   VARCHAR(16)   NOT NULL,
    long_url     VARCHAR(2048) NOT NULL,
    click_count  BIGINT        NOT NULL DEFAULT 0,
    created_at   TIMESTAMP     NOT NULL,
    CONSTRAINT uk_links_short_code UNIQUE (short_code),
    CONSTRAINT ck_links_click_count_non_negative CHECK (click_count >= 0)
);
```
The unique constraint also works as the lookup index for redirects and stats. `created_at` is written from the injected UTC `Clock` as an `Instant`.

## Approvals needed
1. **Public API (new):**
   - `POST /api/v1/links`: returns 201, 400, 429 or 503.
   - `GET /{code}`: returns 302 or 404.
   - `GET /api/v1/links/{code}/stats`: returns 200 or 404. It is public and has no authentication.

   Reviewers should confirm these behaviours:
   - 302 instead of a permanent redirect.
   - Stats are public.
   - Clients are identified by remote address only.
   - Every create request makes a new code, with no deduplication.
2. **Schema (new):** the `links` table:
   - Columns `id`, `short_code` VARCHAR(16), `long_url` VARCHAR(2048), `click_count`, `created_at`.
   - Unique constraint `uk_links_short_code` and check constraint `ck_links_click_count_non_negative`.
   - Applied through `schema.sql` with `spring.sql.init.mode=always`, with no migration tool.
   - Default profile uses a file-based H2 database under `./data/`, which is git-ignored.
3. **Dependencies (new to the `url-shortener` module):**
   - `spring-boot-starter-web`, `spring-boot-starter-data-jdbc`, `spring-boot-starter-validation`, `com.h2database:h2` (runtime) and `spring-boot-starter-test` (test).
   - All of them are on the AC16 allowlist, and all versions come from the Spring Boot 3.3 parent.
   - No Bucket4j, springdoc or Flyway.
   - SnakeYAML in T9 is pulled in by Spring Boot and needs no new declaration.
4. **Configuration surface (new, for operations review):**
   - `app.base-url`, `app.blocked-hosts`, `app.max-url-length`, `app.code-length`, `app.max-code-attempts`.
   - `app.rate-limit.requests` and `app.rate-limit.window`.