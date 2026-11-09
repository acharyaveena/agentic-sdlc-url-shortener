## Problem statement
Links in the URL shortener never expire, so they keep redirecting forever. Each link needs a time-to-live (TTL). After the TTL ends, the redirect endpoint must return `410 Gone` and stop redirecting. Expired links must eventually be deleted so the data store does not grow without limit.

## Ambiguities
- **Where the TTL comes from.** The request says "configurable" but does not say whether that is one service-wide setting, a value per link sent at creation, or both. This decides whether the create API and its OpenAPI spec change.
- **Default and limits.** No default TTL, minimum or maximum is given, and it is unclear whether links can still be created with no expiry. This affects validation rules and how existing links behave.
- **Existing links.** It is unclear whether links created before this change get an expiry. Backfilling could break live links, so this matters.
- **Unknown codes vs. expired codes.** The request does not say whether unknown codes still return `404` while expired ones return `410`, or how long a cleaned-up link keeps returning `410`. After deletion, an expired code can no longer be told apart from one that never existed.
- **When to clean up.** "No longer useful" is not defined, and no cleanup schedule is given. This decides the grace period and the scheduling approach.
- **Expiry boundary and clock.** It is unclear whether a request at exactly `expiresAt` counts as expired, and which timezone or clock to use. Tests need a fixed rule.
- **Click counting and rate limiting.** It is unclear whether hits on expired links count as clicks or use up rate-limit quota. This affects analytics accuracy.
- **Reusing codes.** It is unclear whether the short code of an expired or deleted link can be given to a new link. Reuse could send old shared URLs to the wrong place.
- **Visibility.** It is unclear whether the create response or another lookup endpoint should show the expiry time. This affects the public API surface.
- **Extending or reviving links.** It is unclear whether a link's TTL can be extended or an expired link brought back. This would add an update endpoint.

## Assumptions
- **TTL source:** There is a service-wide default TTL, set with the `shortener.link.default-ttl` property as an ISO-8601 duration with a default of `P30D`. The create request also takes an optional `ttlSeconds` field that overrides the default. This is a public API change and needs human approval.
- **Limits:** `ttlSeconds` must be between `60` and `31536000` (1 year), or the request gets `400 Bad Request`. Every new link gets an expiry, so there are no links that never expire.
- **Schema:** Add a nullable `expires_at TIMESTAMP` column to the links table, with an index for cleanup. This is a schema change and needs human approval.
- **Existing links:** Links that already exist keep `expires_at = NULL`, which means they never expire. They are not backfilled.
- **Expiry boundary:** A link is expired when `now >= expiresAt`. Time is stored and compared in UTC, and the clock is an injectable `java.time.Clock` so tests can control it.
- **Status codes:** Unknown codes still return `404 Not Found`. Expired links that have not been cleaned up return `410 Gone` with a short JSON error body. After cleanup, a code returns `404`.
- **Cleanup:** A scheduled job using Spring `@Scheduled` runs every hour by default (`shortener.link.cleanup-interval`). It deletes links whose `expires_at` is more than the grace period in the past (`shortener.link.cleanup-grace`, default `P7D`). Batch deletes are allowed.
- **Clicks and rate limiting:** Requests for expired links do not increase the click count. They still count toward the rate limit, so the limiter cannot be bypassed.
- **Code reuse:** Codes are not reused while the link row still exists. After cleanup, existing collision handling decides whether a code is free. Any risk from reuse after the grace period is noted, not prevented.
- **Visibility:** The create response includes `expiresAt` as an ISO-8601 UTC string. No new lookup endpoint is added.
- **Extending:** Links cannot be extended or revived.

## Acceptance criteria
1. Given no `ttlSeconds` and the default TTL `P30D`, when a link is created at time T, then the response is `201` and `expiresAt` = T + 30 days.
2. Given `ttlSeconds = 3600`, when a link is created at time T, then `expiresAt` = T + 1 hour and is stored in the `expires_at` column.
3. Given `ttlSeconds` of `59`, `0`, a negative number, or `31536001`, when a link is created, then the response is `400` with a validation error and no row is saved.
4. Given a link with `expiresAt` in the future, when its code is requested, then the response is a redirect to the original URL and the click count goes up by 1.
5. Given a link where `now == expiresAt`, when its code is requested, then the response is `410 Gone`.
6. Given a link where `now > expiresAt` and the row still exists, when its code is requested, then the response is `410 Gone` with a JSON error body, there is no `Location` header, and the click count does not change.
7. Given a code that has never existed, when it is requested, then the response is `404 Not Found`.
8. Given a link created before this change (`expires_at IS NULL`), when its code is requested at any time, then it redirects normally.
9. Given links that expired 8 days ago, 6 days ago, and not yet, and a 7-day grace period, when the cleanup job runs, then only the link that expired 8 days ago is deleted.
10. Given a link that was deleted by cleanup, when its code is requested, then the response is `404`.
11. Given a requester at the rate limit, when they request an expired code, then the response is `429` because the rate limit is checked first.
12. Given the database migration is applied to an existing database, when the service starts, then existing rows are kept, `expires_at` is `NULL` for them, and the expiry index exists.
13. Given the updated OpenAPI spec, when it is checked, then it documents the optional `ttlSeconds` request field, the `expiresAt` response field, and the `410` response on the redirect endpoint.
14. Given the test suite, when `./mvnw verify` runs, then unit tests cover the expiry boundary using a fixed `Clock`, integration tests cover the 201/302/404/410 paths, and cleanup tests pass.

## Out of scope
- Extending, renewing, or reviving a link's TTL, and any update or delete endpoint.
- Expiry based on click count, such as "expire after N clicks".
- Notifying link owners about upcoming or past expiry.
- A permanent record of deleted codes, so `410` is not kept after cleanup.
- Distributed locking for the cleanup job when several instances run; it assumes a single instance.
- Backfilling expiry onto existing links.
- Changes to analytics reporting other than not counting clicks on expired links.
- An admin UI or a lookup endpoint for link metadata.