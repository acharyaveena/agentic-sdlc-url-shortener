# url-shortener

A URL shortener service (Java 21, Spring Boot 3.3, Spring Data JDBC, H2). Built by the agentic orchestrator
from a natural-language request; the generated code failed its first automated build (a constructor
mismatch between the code and tests stages), was recovered from the orchestrator's preserved failed-state
tag, and fixed by a human reviewer. See `scenarios/greenfield/` for the full run evidence.

Link expiry (TTL, 410 Gone, scheduled cleanup) was added afterwards as a brownfield change request. That
run's code and tests stages produced structurally broken output (wrong package, dropped dependencies and
constraints in files they rewrote), so the feature was integrated by hand into the real files instead. See
`scenarios/brownfield/` for the full run evidence and `scenarios/brownfield/walkthrough.md` for why.

Per-link click analytics (referrer/user-agent breakdown, daily series) were added after that from a
deliberately vague request ("make analytics better"). That run's design was rejected and re-planned once
before approval; the code stage then built a different, less consistent integration than what was approved
and had a real bug (a wrong column name), so this feature was also integrated by hand. See
`scenarios/ambiguous-analytics/` for the full run evidence.

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
| `GET` | `/api/v1/links/{code}/analytics` | Get per-link click analytics for a UTC date range |

The full contract is in [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml).

### Create a link

`ttlSeconds` is optional (60 to 31536000); it defaults to `app.link.default-ttl` when omitted.

```bash
curl -i -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/page","ttlSeconds":3600}'
```

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/aB3xQ9z

{"code":"aB3xQ9z","shortUrl":"http://localhost:8080/aB3xQ9z","longUrl":"https://example.com/some/page","createdAt":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T01:00:00Z"}
```

### Visit a short link

```bash
curl -i http://localhost:8080/aB3xQ9z
```

Returns `302 Found` with a `Location` header, and increments that link's click count by one. Once the link
has passed its `expiresAt`, the same request returns `410 Gone` instead, and the click is not counted.
Links created before this feature existed (or with no TTL ever set) have `expiresAt: null` and never expire.

### Read stats

```bash
curl http://localhost:8080/api/v1/links/aB3xQ9z/stats
```

```json
{"code":"aB3xQ9z","longUrl":"https://example.com/some/page","clickCount":1,"createdAt":"2026-01-01T00:00:00Z"}
```

### Read analytics

`from`/`to` are optional ISO dates (UTC, inclusive); they default to the last `app.analytics.default-range-days`
days ending today. `excludeBots` (default `false`) filters out clicks classified as bots. History starts
from when this feature was deployed and can differ from the all-time `clickCount` above. No IP address,
full user-agent string or full referrer URL is ever stored — only a coarse user-agent category
(`BROWSER`/`BOT`/`OTHER`) and the referrer's host.

```bash
curl "http://localhost:8080/api/v1/links/aB3xQ9z/analytics?from=2026-01-01&to=2026-01-03"
```

```json
{
  "code": "aB3xQ9z",
  "from": "2026-01-01",
  "to": "2026-01-03",
  "excludeBots": false,
  "totalClicks": 5,
  "daily": [{"date":"2026-01-01","clicks":3},{"date":"2026-01-02","clicks":0},{"date":"2026-01-03","clicks":2}],
  "topReferrers": [{"host":"a.com","clicks":2}],
  "userAgents": {"BROWSER": 4, "BOT": 1, "OTHER": 0}
}
```

### Errors

All errors are `application/problem+json` (RFC 7807), for example:

```json
{"type":"about:blank","title":"Invalid URL","status":400,"detail":"scheme must be http or https"}
```

| Status | When |
|---|---|
| 400 | Malformed or blocked URL (blocked schemes, length, self-reference, invalid JSON), `ttlSeconds` out of range, or an invalid analytics date range |
| 404 | Unknown short code |
| 410 | Link has passed its `expiresAt` |
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
| `app.link.default-ttl` | `P30D` | Time-to-live applied when a create request omits `ttlSeconds` |
| `app.link.cleanup-interval` | `PT1H` | How often the expired-link cleanup job runs |
| `app.link.cleanup-grace` | `P7D` | How long an expired link is kept (for stats/audit) before cleanup deletes it |
| `app.analytics.default-range-days` | 30 | Days covered by `/analytics` when `from`/`to` are omitted |
| `app.analytics.max-range-days` | 366 | Largest allowed `from`-`to` span before a 400 |
| `app.analytics.top-referrers-limit` | 10 | Maximum number of referrer hosts returned |

Rate limiting applies only to `POST /api/v1/links`, by remote address (no proxy headers are trusted). The
cleanup job only deletes rows whose `expires_at` is non-null and older than the grace period; links with
no expiry are never touched. Click events are recorded in the same database transaction as the click
counter increment, so a failure to record an event fails the redirect rather than silently losing data.

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

162 tests (unit and integration) pass, including concurrent-redirect, rate-limit, link-expiry and
link-analytics integration tests against a real (in-memory) H2 database with a fixed clock.
