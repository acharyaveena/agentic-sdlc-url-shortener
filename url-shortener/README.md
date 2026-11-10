# URL shortener

A Spring Boot 3.3 service on Java 21, using Spring Data JDBC and H2.

## Features

- Create short links for http and https URLs
- Redirect and count clicks
- Click stats per link
- Link expiry, with `410 Gone` and a cleanup job
- Per-link analytics: daily clicks, top referrer hosts, browser or bot
- Collision-safe short codes
- Rate limiting on link creation

## How it was built

| Feature | Scenario | Result |
|---|---|---|
| Core service | [Greenfield](../scenarios/greenfield/walkthrough.md) | Third live run recovered and fixed by a human |
| Link expiry | [Brownfield](../scenarios/brownfield/walkthrough.md) | Generated code was broken, so the feature was rebuilt by hand |
| Analytics | [Ambiguous](../scenarios/ambiguous-analytics/walkthrough.md) | Generated code ignored the design, so the integration was rebuilt by hand |

## Run it

From the repo root:

```bash
./mvnw -pl url-shortener spring-boot:run
```

- Runs on `http://localhost:8080`.
- Stores data in `url-shortener/data/` (H2 file mode).
- Step-by-step commands for Windows and macOS/Linux are in [docs/SETUP.md](../docs/SETUP.md#run-the-url-shortener).

## API

| Method | Path | Does |
|---|---|---|
| `POST` | `/api/v1/links` | Create a short link |
| `GET` | `/{code}` | Redirect and count the click |
| `GET` | `/api/v1/links/{code}/stats` | Click count and metadata |
| `GET` | `/api/v1/links/{code}/analytics` | Daily clicks, referrers and user agents |

The full contract is in [openapi.yaml](src/main/resources/openapi.yaml).

### Create a link

```bash
curl -X POST http://localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/page","ttlSeconds":3600}'
```

```json
{"code":"aB3xQ9z","shortUrl":"http://localhost:8080/aB3xQ9z","longUrl":"https://example.com/page","createdAt":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T01:00:00Z"}
```

- `ttlSeconds` is optional, from 60 to 31536000.
- Without it, links expire after 30 days.

### Visit a link

```bash
curl -i http://localhost:8080/aB3xQ9z
```

- Returns `302` with the original URL in `Location`.
- Adds one click.
- After the link expires it returns `410` and does not count the click.
- Links with no expiry date never expire.

### Stats

```bash
curl http://localhost:8080/api/v1/links/aB3xQ9z/stats
```

```json
{"code":"aB3xQ9z","longUrl":"https://example.com/page","clickCount":1,"createdAt":"2026-01-01T00:00:00Z"}
```

### Analytics

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

- `from` and `to` are optional UTC dates. They default to the last 30 days.
- `excludeBots=true` leaves out bot clicks.
- Only the referrer host and a user agent category are stored. No IP address, full URL or full user agent.
- Analytics start from when the feature was deployed, so totals can be lower than `clickCount`.

### Errors

All errors use `application/problem+json`.

```json
{"type":"about:blank","title":"Invalid URL","status":400,"detail":"scheme must be http or https"}
```

| Status | When |
|---|---|
| 400 | Bad URL, bad JSON, `ttlSeconds` out of range, or bad analytics dates |
| 404 | Unknown code |
| 410 | Link has expired |
| 429 | Too many creates. Includes a `Retry-After` header. |
| 503 | No free short code after 5 tries |

## Configuration

Settings live in [application.yml](src/main/resources/application.yml) under `app`.

| Property | Default | Meaning |
|---|---|---|
| `app.base-url` | `http://localhost:8080` | Used to build `shortUrl` and block self-links |
| `app.blocked-hosts` | `localhost, 127.0.0.1, ::1` | Hosts a URL may not point to |
| `app.max-url-length` | 2048 | Longest accepted URL |
| `app.code-length` | 7 | Short code length |
| `app.max-code-attempts` | 5 | Tries before returning 503 |
| `app.rate-limit.requests` | 10 | Creates allowed per client per window |
| `app.rate-limit.window` | `PT1M` | Rate limit window |
| `app.link.default-ttl` | `P30D` | Expiry when `ttlSeconds` is not given |
| `app.link.cleanup-interval` | `PT1H` | How often expired links are cleaned up |
| `app.link.cleanup-grace` | `P7D` | How long an expired link is kept before deletion |
| `app.analytics.default-range-days` | 30 | Default analytics range |
| `app.analytics.max-range-days` | 366 | Longest allowed range |
| `app.analytics.top-referrers-limit` | 10 | Referrer hosts returned |

## Behavior notes

- Rate limiting applies only to creating links. It is keyed by client address.
- Cleanup deletes only links that expired more than the grace period ago.
- A click and its analytics event are saved in one transaction. If either fails, the redirect fails.

## Limitations

- The rate limiter is in memory and per instance.
- There is no authentication.
- H2 is not meant for production.
- Self-link checks match exact hosts, without DNS lookups.

## Tests

```bash
./mvnw -pl url-shortener verify
```

162 unit and integration tests run against in-memory H2. See [docs/TESTING.md](../docs/TESTING.md).
