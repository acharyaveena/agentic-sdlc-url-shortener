## Design overview
This is a layered Spring Boot 3.3 service in `url-shortener/` under the package `com.example.urlshortener`. The sub-packages are `domain`, `repository`, `service`, `web`, `ratelimit` and `config`. The key decisions and their trade-offs:

- **Hand-written `schema.sql` with JPA `ddl-auto=validate`.** The DDL is explicit and we add no Flyway dependency. The cost is that there are no versioned migrations. The brownfield expiry scenario will have to edit `schema.sql` and get approval for it.
- **7-character Base62 codes from `SecureRandom`, made unique by the DB `UNIQUE` constraint.** There are about 3.5×10¹² possible codes, so collisions are rare and codes are hard to guess. Because the database is the only judge of uniqueness, there is no check-then-insert race. Each attempt uses `saveAndFlush` and catches `DataIntegrityViolationException`. There are at most 5 attempts, then `CodeGenerationExhaustedException` → 503. Each attempt runs in its own transaction through `TransactionTemplate`, so a failed insert does not poison the next one. The trade-off is that the retry loop lives in the service, not in a declarative retry, which keeps it easy to test.
- **`CodeGenerator` is an interface.** `RandomCodeGenerator` is the real implementation, and tests stub it for AC 10 and 11.
- **`UrlValidator` is a plain class that returns typed errors.** It trims the input, checks `length ≤ app.max-url-length` (2048), parses with `java.net.URI`, requires an absolute `http`/`https` URI with a host and no user info, and rejects blocked hosts. Blocked hosts are the host of `app.base-url`, `app.blocked-hosts`, `127.0.0.0/8` matched as a literal IPv4 prefix, `::1` and `0.0.0.0`. The trade-off is that it does no DNS resolution: a hostname that resolves to loopback is not caught. That is acceptable for now and recorded as a limitation.
- **302 redirect plus an atomic `UPDATE`.** `@Modifying @Query("update ShortLink s set s.clickCount = s.clickCount + 1, s.lastAccessedAt = :now where s.code = :code")` returns the row count, so 0 means 404. Concurrent clicks are never lost (AC 5). It costs one write per redirect, which is acceptable for H2 and a single instance.
- **In-house token-bucket rate limiter in `ratelimit`.** `RateLimiter` is an interface and `TokenBucketRateLimiter` keeps a `ConcurrentHashMap<String, Bucket>` with synchronized refill. It takes an injectable `Clock` so tests are deterministic. `RateLimitInterceptor` is a `HandlerInterceptor` registered only for `POST /api/v1/links`. `ClientIdResolver` uses `remoteAddr` and reads `X-Forwarded-For` only when trust is enabled. The trade-off is state in one process, an unbounded map that stale-bucket eviction keeps small, and no new dependency.
- **Errors use Spring 6 `ProblemDetail`** through a `@RestControllerAdvice`. Problem types are `/problems/invalid-url`, `/problems/self-referencing-url`, `/problems/not-found`, `/problems/rate-limited` and `/problems/code-generation-exhausted`. Malformed JSON and bean-validation failures are mapped to 400.
- **Static OpenAPI file** at `src/main/resources/static/openapi.yaml`. A contract test parses it and compares paths with `RequestMappingHandlerMapping`. Parsing uses SnakeYAML, which `spring-boot-starter` already brings in transitively, so no new dependency is added. The trade-off is that the spec can drift from the code, and the contract test catches that.
- **JaCoCo plugin with a `check` rule** of ≥ 80% line coverage on `service` and `web`, run during `verify`.

## Task plan
| id | task | depends on | stage |
|----|------|-----------|-------|
| T1 | `pom.xml`: add the allowlisted starters (web, data-jpa, validation), `h2` and `starter-test`, and the JaCoCo plugin with a `check` rule of ≥ 0.80 lines on `service` and `web` (AC 17, 18) | – | code |
| T2 | `application.yml`: `app.base-url`, `app.max-url-length=2048`, `app.blocked-hosts`, `app.code.length=7`, `app.code.max-attempts=5`, `app.rate-limit.capacity=10`, `app.rate-limit.refill-period=PT1M`, `app.rate-limit.trust-forwarded-for=false`, `spring.jpa.hibernate.ddl-auto=validate`, `spring.sql.init.mode=always`. Add an `AppProperties` `@ConfigurationProperties` record | T1 | code |
| T3 | `schema.sql` (table `short_link`) plus the `ShortLink` entity and `ShortLinkRepository` with `findByCode` and an atomic `incrementClicks(code, now)` (AC 2, 4, 5, 15) | T1 | code |
| T4 | `CodeGenerator` interface and `RandomCodeGenerator` (SecureRandom, Base62, configurable length) (AC 1) | T2 | code |
| T5 | `UrlValidator` covering trim, length, scheme, host, user info and self-link host checks, throwing `InvalidUrlException` or `SelfReferencingUrlException` (AC 6–9) | T2 | code |
| T6 | `ShortLinkService`: `create(url)` validates, then loops up to `maxAttempts` running generate → `saveAndFlush` in a new transaction and catching `DataIntegrityViolationException`, and throws `CodeGenerationExhaustedException` when attempts run out. Also `resolveAndCount(code)` and `stats(code)` (AC 1, 2, 4, 5, 10, 11) | T3, T4, T5 | code |
| T7 | `RateLimiter` interface, `TokenBucketRateLimiter` (Clock-injected, returns the allow decision and retry-after seconds), `ClientIdResolver`, and a `RateLimitInterceptor` registered in `WebConfig` for `POST /api/v1/links` only (AC 12–14) | T2 | code |
| T8 | Controllers: `LinkController` (`POST /api/v1/links` → 201 + Location; `GET /api/v1/links/{code}/stats`) and `RedirectController` (`GET /{code:[0-9A-Za-z]{1,16}}` → 302). Request/response records use `@NotBlank` on `url` (AC 1–4, 9) | T6 | code |
| T9 | `GlobalExceptionHandler` that maps exceptions to `ProblemDetail`s: 400 invalid-url / self-referencing-url / malformed body / validation, 404 not-found, 429 + `Retry-After`, and 503 code-generation-exhausted (AC 3, 6–9, 11, 12) | T6, T7 | code |
| T10 | `static/openapi.yaml` (OpenAPI 3.0.3) with the 3 operations and the 201/302/400/404/429/503 responses (AC 16) | – | code |
| T11 | `RandomCodeGeneratorTest`: length 7, Base62 only, values differ across calls (AC 1) | T4 | tests |
| T12 | `UrlValidatorTest`, parameterized: ftp/javascript/file/data/no scheme, length 2048 accepted and 2049 rejected, base host, localhost, 127.0.0.1, 127.5.5.5, [::1], 0.0.0.0, any port, user info, blank (AC 6–9) | T5 | tests |
| T13 | `ShortLinkServiceTest` with a mocked repository and stubbed generator: collision twice then success, stored code is the free one (AC 10); always colliding gives exactly 5 `generate()` calls and the exhausted exception (AC 11); invalid URL never touches the repository (AC 6) | T6 | tests |
| T14 | `TokenBucketRateLimiterTest` with a fixed/mutable `Clock`: 10 allowed then the 11th denied with retry-after > 0, refill after one minute, separate keys are independent (AC 12, 13) | T7 | tests |
| T15 | `LinkApiIntegrationTest` (`@SpringBootTest` + MockMvc): create 201 + Location + body (AC 1), redirect 302 (AC 2), 404 problem+json on both endpoints (AC 3), 3 clicks → stats 3 and `lastAccessedAt` set (AC 4), each invalid input → 400 with row count unchanged (AC 6–9) | T8, T9 | tests |
| T16 | `ConcurrentClickIntegrationTest` (`RANDOM_PORT`, a 50-thread executor with `TestRestTemplate` and redirects disabled) → `clickCount == 50` (AC 5) | T8 | tests |
| T17 | `RateLimitIntegrationTest`: set `remoteAddr` with a MockMvc `with(request -> ...)` post-processor; 10×201 then 429 + `Retry-After` (AC 12); another IP gets 201 (AC 13); the blocked IP can still redirect and read stats (AC 14). Use `@DirtiesContext` or a reset hook to isolate limiter state | T7, T8, T9 | tests |
| T18 | `CollisionIntegrationTest`: a `@MockBean CodeGenerator` returns a pre-inserted code twice and then a free one → 201 with the free code; always taken → 503 `code-generation-exhausted` (AC 10, 11 end-to-end) | T8, T9 | tests |
| T19 | `SchemaIntegrationTest`: the context starts (JPA validate passes), `INFORMATION_SCHEMA` shows `short_link` with a unique constraint on `code`, and a duplicate insert throws (AC 15) | T3 | tests |
| T20 | `OpenApiContractTest`: parse the YAML, assert `openapi: 3.*`, the 3 operations, and that response codes {201, 302, 400, 404, 429, 503} are present; every spec path/method matches a `RequestMappingHandlerMapping` entry (AC 16) | T8, T10 | tests |
| T21 | Run `./mvnw verify`, confirm JaCoCo is ≥ 80% on `service` and `web`, and confirm all changed files are under `url-shortener/` with no secrets (AC 17, 18) | T11–T20 | tests |

Tests can start against interfaces and signatures as soon as each code task defines them. The code and test stages join at T21.

## API changes
```yaml
openapi: 3.0.3
info:
  title: URL Shortener API
  version: 1.0.0
paths:
  /api/v1/links:
    post:
      operationId: createLink
      summary: Create a short link
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
              description: Short URL
              schema: { type: string, format: uri }
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LinkResponse'
        '400':
          $ref: '#/components/responses/Problem'
        '429':
          description: Rate limit exceeded
          headers:
            Retry-After:
              description: Seconds until a new request is allowed
              schema: { type: integer, minimum: 1 }
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/ProblemDetail'
        '503':
          $ref: '#/components/responses/Problem'
  /api/v1/links/{code}/stats:
    get:
      operationId: getLinkStats
      summary: Get click statistics for a short link
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
          $ref: '#/components/responses/Problem'
  /{code}:
    get:
      operationId: redirect
      summary: Redirect to the target URL and count the click
      parameters:
        - $ref: '#/components/parameters/Code'
      responses:
        '302':
          description: Redirect to the target URL
          headers:
            Location:
              schema: { type: string, format: uri }
        '404':
          $ref: '#/components/responses/Problem'
components:
  parameters:
    Code:
      name: code
      in: path
      required: true
      schema:
        type: string
        pattern: '^[0-9A-Za-z]{1,16}$'
  responses:
    Problem:
      description: Error (RFC 7807)
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/ProblemDetail'
  schemas:
    CreateLinkRequest:
      type: object
      required: [url]
      properties:
        url:
          type: string
          format: uri
          maxLength: 2048
          example: https://example.com/a?b=1
    LinkResponse:
      type: object
      required: [code, shortUrl, targetUrl, createdAt]
      properties:
        code: { type: string, pattern: '^[0-9A-Za-z]{7}$' }
        shortUrl: { type: string, format: uri }
        targetUrl: { type: string, format: uri }
        createdAt: { type: string, format: date-time }
    LinkStats:
      type: object
      required: [code, targetUrl, clickCount, createdAt]
      properties:
        code: { type: string }
        targetUrl: { type: string, format: uri }
        clickCount: { type: integer, format: int64, minimum: 0 }
        createdAt: { type: string, format: date-time }
        lastAccessedAt: { type: string, format: date-time, nullable: true }
    ProblemDetail:
      type: object
      properties:
        type:
          type: string
          format: uri-reference
          enum:
            - /problems/invalid-url
            - /problems/self-referencing-url
            - /problems/not-found
            - /problems/rate-limited
            - /problems/code-generation-exhausted
        title: { type: string }
        status: { type: integer }
        detail: { type: string }
        instance: { type: string }
```

## Schema changes
```sql
CREATE TABLE IF NOT EXISTS short_link (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    code             VARCHAR(16)   NOT NULL,
    target_url       VARCHAR(2048) NOT NULL,
    click_count      BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMP     NOT NULL,
    last_accessed_at TIMESTAMP     NULL,
    CONSTRAINT uk_short_link_code UNIQUE (code)
);
```

## Approvals needed
1. **New public API:** `POST /api/v1/links`, `GET /{code}` (302 redirect) and `GET /api/v1/links/{code}/stats`, the problem+json error types, and the 429 `Retry-After` contract. The catch-all `/{code}` path reserves the root namespace. Approve the path pattern `[0-9A-Za-z]{1,16}`.
2. **New schema:** table `short_link` with the unique constraint `uk_short_link_code`. It is managed by a hand-written `schema.sql` with no migration tool.
3. **New dependencies (all on the allowlist):**
   - `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation` and `com.h2database:h2` (runtime).
   - `spring-boot-starter-test` (test).
   - Build plugin `org.jacoco:jacoco-maven-plugin`. This is a build plugin, not a runtime dependency, and needs explicit sign-off.
   - SnakeYAML is used only through the existing transitive dependency from `spring-boot-starter`, so no new artifact is declared.
4. **Limitations to accept:**
   - The self-link check does not use DNS, so a hostname that resolves to loopback is not caught.
   - Rate-limit state lives in one process only.
   - `X-Forwarded-For` is ignored by default.