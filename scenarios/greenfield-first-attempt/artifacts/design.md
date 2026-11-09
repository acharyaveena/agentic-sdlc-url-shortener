## Design overview
This design builds a single Spring Boot 3.3 service in `url-shortener/` with these packages: `api`, `service`, `validation`, `code`, `ratelimit`, `persistence` and `config`. Code tasks and test tasks share fixed contracts: the interfaces, DTOs, error codes and the API below. That lets the code and tests stages run in parallel.

| Decision | Choice | Trade-off |
|---|---|---|
| Persistence | `JdbcTemplate` from `spring-boot-starter-data-jdbc`, with a plain `schema.sql` | Click counting needs an atomic SQL `UPDATE`, and the unique-key violation is easy to catch. We give up ORM convenience, which a single table does not need. |
| Database | H2 in-memory by default; a file-based URL can be set through `spring.datasource.url` | Data is lost on restart by default. In return the setup is simple and tests are isolated. |
| Collision handling | The DB unique constraint is the source of truth. `LinkService` catches `DuplicateKeyException` and retries up to `app.code.max-attempts=5`, then returns 503 `CODE_GENERATION_EXHAUSTED`. | Inserting and catching the error has no race window, unlike checking first and then inserting. Each collision costs one failed insert, which is rare: 62^7 ≈ 3.5×10^12 possible codes. |
| Code generation | A `CodeGenerator` interface with `SecureRandomBase62CodeGenerator` (7 characters) | Codes are hard to guess, and tests can stub the generator to force collisions. Codes are random rather than sequential, so creation needs the retry loop. |
| URL validation | A pure `UrlValidator` that uses `java.net.URI`: http or https only, host required, no user-info, at most 2048 characters after trimming, and a self-reference block list (the `app.base-url` host, `localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0`). It does no DNS lookups. | It is fast and deterministic. It does not catch DNS aliases or redirect chains, which are out of scope. |
| Rate limiting | A hand-written `TokenBucketRateLimiter` (a `ConcurrentHashMap` of buckets, with an injectable `Clock`) behind a `RateLimiter` interface. A `HandlerInterceptor` applies it only to `POST /api/links`. | No new dependency, and only valid for a single instance. Buckets that sit idle for more than 10 minutes are removed when the limiter is next used, so memory stays bounded. |
| Client identity | `ClientIdResolver` uses the remote IP. The first `X-Forwarded-For` entry is used only when `app.rate-limit.trust-forwarded-for=true`. | This is safe by default. Clients behind a shared NAT share one bucket. |
| Redirects | 302 with a `Location` header. The click is counted with `UPDATE links SET click_count = click_count + 1 WHERE code = ?`, and 0 updated rows means 404. | Every visit reaches the server and is counted exactly. We lose browser caching. |
| Errors | `@RestControllerAdvice` returns `{"error","message"}` with these error codes: `INVALID_URL`, `URL_TOO_LONG`, `SELF_REFERENCE`, `INVALID_REQUEST`, `NOT_FOUND`, `RATE_LIMITED`, `CODE_GENERATION_EXHAUSTED`. | The error contract is stable and easy to test. |
| Routing | The redirect is mapped as `GET /{code:[0-9A-Za-z]{1,16}}`. | Codes cannot collide with `/api/**` or the actuator endpoints. |
| OpenAPI | Hand-written `src/main/resources/openapi.yaml`, checked in a test with `swagger-parser` (test scope only) | No runtime dependency. The spec can drift from the code, but the test checks that it is valid and lists the expected paths and status codes. |
| Coverage | The JaCoCo Maven plugin enforces at least 80% line coverage on `service` and `validation` during `verify`. | It is a build plugin, not a runtime dependency, but it still needs approval. |

## Task plan
| id | task | depends on | stage |
|---|---|---|---|
| T1 | Module skeleton: `pom.xml` with the starters web, data-jdbc and validation, the H2 runtime driver, and starter-test; `UrlShortenerApplication`; `application.yml` with `app.base-url`, `app.code.length=7`, `app.code.max-attempts=5`, `app.rate-limit.capacity=10`, `app.rate-limit.refill-period=PT1M`, `app.rate-limit.trust-forwarded-for=false` and an H2 in-memory datasource; a `@ConfigurationProperties` class `AppProperties` | — | code |
| T2 | `schema.sql` with the `links` table, its unique constraint and defaults (AC16, AC17) | T1 | code |
| T3 | Contracts: the `CodeGenerator`, `RateLimiter` (`tryAcquire(clientId)` returns a `RateLimitDecision(allowed, retryAfterSeconds)`) and `LinkRepository` interfaces; the DTOs `CreateLinkRequest`, `LinkResponse` and `LinkStatsResponse`; `ErrorResponse`; the `ErrorCode` enum; the domain exceptions | T1 | code |
| T4 | `SecureRandomBase62CodeGenerator` | T3 | code |
| T5 | `UrlValidator`: trim, length check (`URL_TOO_LONG`), parse, scheme, host and user-info checks (`INVALID_URL`), self-reference block list (`SELF_REFERENCE`) (AC4, AC5, AC7) | T3 | code |
| T6 | `JdbcLinkRepository`: `insert`, `findByCode`, `incrementClicks` (atomic, returns the number of updated rows) | T2, T3 | code |
| T7 | `LinkService`: `create` (validate, then a generate-and-insert retry loop with `max-attempts`, throwing `CodeGenerationExhaustedException`), `resolveAndCount` and `stats` (AC1, AC8, AC9, AC10) | T4, T5, T6 | code |
| T8 | `TokenBucketRateLimiter` (with `Clock`, `retryAfter` = time until the next token, rounded up and at least 1), `ClientIdResolver`, `RateLimitInterceptor` and its `WebMvcConfigurer` registration for `POST /api/links` only (AC13, AC14, AC15) | T3 | code |
| T9 | Controllers: `LinkController` (`POST /api/links` returns 201 with a `Location` header; `GET /api/links/{code}/stats`) and `RedirectController` (`GET /{code}` returns 302) (AC1, AC2, AC3, AC10, AC12) | T7 | code |
| T10 | `GlobalExceptionHandler`: maps the domain exceptions, `MethodArgumentNotValidException` and `HttpMessageNotReadableException` to 400 `INVALID_REQUEST`, 404, 429 with `Retry-After`, and 503 (AC3, AC4–AC6, AC9, AC12, AC13) | T3 | code |
| T11 | `openapi.yaml` (OpenAPI 3.0) covering the three operations, their schemas and the status codes 201, 302, 400, 404, 429 and 503 (AC18) | T3 | code |
| T12 | JaCoCo plugin with a `check` goal (line coverage at least 0.80 for `service` and `validation`) and the `swagger-parser` test dependency (AC19, AC20) | T1 | code |
| U1 | `UrlValidatorTest`: valid http and https; ftp, javascript, file, mailto and missing-scheme URLs; user-info; missing host; 2048 vs 2049 characters; blank input; self-reference to the base-url host, `localhost`, `127.0.0.1`, `127.5.5.5`, `[::1]`, `0.0.0.0` and an upper-case host (AC4, AC5, AC7) | T3 | tests |
| U2 | `SecureRandomBase62CodeGeneratorTest`: the length is 7, every character matches `[0-9A-Za-z]`, and 10,000 generated codes are distinct | T3 | tests |
| U3 | `LinkServiceTest` with a stubbed generator and repository: two collisions then success means 3 attempts (AC8); always colliding means 5 attempts, an exhausted exception and no successful insert (AC9); stats for an unknown code means not found | T3 | tests |
| U4 | `TokenBucketRateLimiterTest` with a fixed `Clock`: 10 requests allowed and the 11th denied with `retryAfter` > 0 (AC13); separate clients have separate buckets (AC14); the bucket refills after the period; idle buckets are evicted | T3 | tests |
| U5 | `ClientIdResolverTest`: the remote address is used by default, and `X-Forwarded-For` is used (first entry) only when trusted | T3 | tests |
| I1 | `LinkApiIntegrationTest` (`@SpringBootTest` + MockMvc): create returns 201 with the right body and `Location` (AC1); redirect returns 302 (AC2); unknown code returns 404 with a JSON body (AC3); invalid, too-long, self-reference, blank, missing and non-JSON bodies return 400 (AC4–AC7); stats after 3 redirects shows 3 (AC10); unknown stats returns 404 (AC12) | T3 | tests |
| I2 | `CollisionIntegrationTest` with a `@MockBean CodeGenerator` stubbed to collide (seeded code): the 3-attempt case succeeds, and the always-colliding case returns 503 with an unchanged row count (AC8, AC9) | T3 | tests |
| I3 | `ConcurrentClickIntegrationTest`: 50 parallel redirects through an `ExecutorService` give `clickCount` = 50 (AC11) | T3 | tests |
| I4 | `RateLimitIntegrationTest`: 11 POSTs from IP A give 10×201 and then 429 with `Retry-After` > 0; IP B gets 201; IP A still gets 302 and 200 on redirect and stats (AC13–AC15). The context is isolated with `@DirtiesContext`. | T3 | tests |
| I5 | `SchemaIntegrationTest`: query `INFORMATION_SCHEMA` for the columns, types, NOT NULL constraints, the default and the unique constraint; insert a duplicate code and expect `DuplicateKeyException` (AC16, AC17) | T2 | tests |
| I6 | `OpenApiSpecTest`: `OpenAPIV3Parser` parses the spec with no messages; the 3 paths and their status codes exist (AC18) | T11, T12 | tests |
| V1 | Run `./mvnw verify`: all tests pass, the JaCoCo check passes, and `dependency:list` shows only approved artifacts (AC19, AC20) | T1–T12, U1–U5, I1–I6 | tests |

The test tasks depend only on T3 (the contracts) and T2, T11 and T12 where noted. They can be written in parallel with T4–T10.

## API changes
```yaml
openapi: 3.0.3
info:
  title: URL Shortener API
  version: 1.0.0
servers:
  - url: http://localhost:8080
paths:
  /api/links:
    post:
      operationId: createLink
      summary: Create a short link for a long URL
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
              description: Short URL of the created link
              schema: { type: string, format: uri }
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LinkResponse'
        '400':
          description: Invalid request (INVALID_REQUEST, INVALID_URL, URL_TOO_LONG, SELF_REFERENCE)
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
        '429':
          description: Creation rate limit exceeded (RATE_LIMITED)
          headers:
            Retry-After:
              description: Seconds until a new creation is allowed
              schema: { type: integer, minimum: 1 }
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
        '503':
          description: No unique code found within the retry limit (CODE_GENERATION_EXHAUSTED)
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
  /{code}:
    get:
      operationId: redirect
      summary: Redirect to the long URL and count the click
      parameters:
        - $ref: '#/components/parameters/Code'
      responses:
        '302':
          description: Redirect to the stored long URL
          headers:
            Location:
              schema: { type: string, format: uri }
        '404':
          description: Unknown code (NOT_FOUND)
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
  /api/links/{code}/stats:
    get:
      operationId: getLinkStats
      summary: Get click statistics for a link
      parameters:
        - $ref: '#/components/parameters/Code'
      responses:
        '200':
          description: Link statistics
          content:
            application/json:
              schema: { $ref: '#/components/schemas/LinkStatsResponse' }
        '404':
          description: Unknown code (NOT_FOUND)
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
components:
  parameters:
    Code:
      name: code
      in: path
      required: true
      schema: { type: string, pattern: '^[0-9A-Za-z]{1,16}$' }
  schemas:
    CreateLinkRequest:
      type: object
      required: [url]
      properties:
        url: { type: string, maxLength: 2048, example: 'https://example.com/page' }
    LinkResponse:
      type: object
      required: [code, shortUrl, longUrl, createdAt]
      properties:
        code: { type: string, pattern: '^[0-9A-Za-z]{7}$' }
        shortUrl: { type: string, format: uri }
        longUrl: { type: string, format: uri }
        createdAt: { type: string, format: date-time }
    LinkStatsResponse:
      type: object
      required: [code, longUrl, createdAt, clickCount]
      properties:
        code: { type: string }
        longUrl: { type: string, format: uri }
        createdAt: { type: string, format: date-time }
        clickCount: { type: integer, format: int64, minimum: 0 }
    ErrorResponse:
      type: object
      required: [error, message]
      properties:
        error:
          type: string
          enum: [INVALID_REQUEST, INVALID_URL, URL_TOO_LONG, SELF_REFERENCE, NOT_FOUND, RATE_LIMITED, CODE_GENERATION_EXHAUSTED]
        message: { type: string }
```

## Schema changes
```sql
CREATE TABLE IF NOT EXISTS links (
    id          BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code        VARCHAR(16)   NOT NULL,
    long_url    VARCHAR(2048) NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    click_count BIGINT        DEFAULT 0 NOT NULL,
    CONSTRAINT uk_links_code UNIQUE (code)
);
```

## Approvals needed
1. **Public API (new):**
   - `POST /api/links`: returns 201, 400, 429 (with `Retry-After`) or 503.
   - `GET /{code}`: returns 302 or 404.
   - `GET /api/links/{code}/stats`: returns 200 or 404.
   - The JSON error contract `{error, message}` and its error codes.
   - The hand-written `openapi.yaml`.
2. **Schema (new):** table `links` with the unique constraint `uk_links_code`, managed through `schema.sql` (no Flyway).
3. **Runtime dependencies:**
   - `spring-boot-starter-web`
   - `spring-boot-starter-data-jdbc`
   - `spring-boot-starter-validation`
   - `com.h2database:h2` (runtime scope)

   All are Spring Boot-managed versions.
4. **Test dependencies:**
   - `spring-boot-starter-test`, which is standard.
   - `io.swagger.parser.v3:swagger-parser` (test scope), **called out as the single exception in AC20**. It is needed to validate `openapi.yaml` in a test.
5. **Build plugin:** `org.jacoco:jacoco-maven-plugin`. It enforces the 80% line-coverage gate in `verify` and is not packaged into the application.
6. **Configuration defaults that affect behaviour:**
   - `app.base-url=http://localhost:8080`
   - Rate limit of 10 per minute per IP
   - `trust-forwarded-for=false`
   - At most 5 code-generation attempts