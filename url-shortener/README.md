# url-shortener

A URL shortener service (Java 21, Spring Boot 3.3, Spring Data JDBC, H2). Built by the agentic orchestrator
from a natural-language request; the generated code failed its first automated build (a constructor
mismatch between the code and tests stages), was recovered from the orchestrator's preserved failed-state
tag, and fixed by a human reviewer. See `scenarios/greenfield/` for the full run evidence.

## Run it

```bash
./mvnw -pl url-shortener spring-boot:run
```

The service listens on port 8080 and stores data in `url-shortener/data/urlshortener.mv.db` (H2 file mode).

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/links` | Create a short link for a long URL |
| `GET` | `/{code}` | Redirect to the original URL and count the click |
| `GET` | `/api/v1/links/{code}/stats` | Get a link's click count and metadata |

The full contract is in [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml).

### Create a link

```bash
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/page"}'
```

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/aB3xQ9z

{"code":"aB3xQ9z","shortUrl":"http://localhost:8080/aB3xQ9z","longUrl":"https://example.com/some/page","createdAt":"2026-01-01T00:00:00Z"}
```

### Visit a short link

```bash
curl -i http://localhost:8080/aB3xQ9z
```

Returns `302 Found` with a `Location` header, and increments that link's click count by one.

### Read stats

```bash
curl http://localhost:8080/api/v1/links/aB3xQ9z/stats
```

```json
{"code":"aB3xQ9z","longUrl":"https://example.com/some/page","clickCount":1,"createdAt":"2026-01-01T00:00:00Z"}
```

### Errors

All errors are `application/problem+json` (RFC 7807), for example:

```json
{"type":"about:blank","title":"Invalid URL","status":400,"detail":"scheme must be http or https"}
```

| Status | When |
|---|---|
| 400 | Malformed or blocked URL (blocked schemes, length, self-reference, invalid JSON) |
| 404 | Unknown short code |
| 429 | Rate limit exceeded (`Retry-After` header included) |
| 503 | No unique short code found after `app.max-code-attempts` tries |

## Configuration

All settings are under the `app.*` prefix in [`src/main/resources/application.yml`](src/main/resources/application.yml):

| Property | Default | Meaning |
|---|---|---|
| `app.base-url` | `http://localhost:8080` | Host used to build `shortUrl` and to detect self-referencing links |
| `app.blocked-hosts` | `localhost, 127.0.0.1, ::1` | Hosts a submitted URL may not point to |
| `app.max-url-length` | 2048 | Maximum accepted URL length |
| `app.code-length` | 7 | Length of generated short codes (Base62) |
| `app.max-code-attempts` | 5 | Collision retries before returning 503 |
| `app.rate-limit.requests` | 10 | Link creations allowed per client per window |
| `app.rate-limit.window` | `PT1M` | Rate-limit window (ISO-8601 duration) |

Rate limiting applies only to `POST /api/v1/links`, by remote address (no proxy headers are trusted).

## Known limitations

- The rate limiter is in-memory and per-instance; it does not work across multiple instances.
- Self-reference checks use exact host matching only: no DNS resolution, so a hostname that resolves to a
  blocked IP is not caught.
- There is no authentication; link creation, redirects and stats are all open.
- H2 is file-based by default and is not intended for production use as-is.

## Build and test

```bash
./mvnw -pl url-shortener verify
```

82 tests (unit and integration) pass, including concurrent-redirect and rate-limit integration tests
against a real (in-memory) H2 database.
