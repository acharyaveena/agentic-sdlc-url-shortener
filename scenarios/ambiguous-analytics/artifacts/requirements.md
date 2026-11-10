## Problem statement
The request asks to "make analytics better" for the URL shortener, but doesn't say what is wrong with today's analytics or what "better" means. The current baseline is click counting on redirect, with no time breakdown and no read API documented. This change will add richer, queryable per-link analytics (time-bucketed click history and basic referrer/user-agent breakdowns) through a read-only API. Redirects must keep working as they do now, and existing click counts must stay correct.

## Ambiguities
- **What "better" means.** It could mean more metrics, more accurate metrics, faster queries, a dashboard, or exports. This sets the whole scope and design.
- **Which metrics.** Possible options are time series, referrer, user agent or device, geography, unique visitors, and bot filtering. Each one adds schema, privacy cost, or performance cost.
- **Who uses it.** It could be the link owner, an admin, or the public. This affects whether the API needs auth, and the project rules say not to add Spring Security.
- **Privacy.** Storing raw IP, full user agent or referrer is personal data. It brings retention and anonymization obligations.
- **Retention and granularity.** Raw events kept forever grow without limit in H2. Aggregates lose detail.
- **Effect on redirect latency.** Recording more data on the redirect hot path could slow redirects. It is unclear whether recording should be synchronous or asynchronous.
- **Existing click count.** It is unclear whether the current counter is kept, replaced by events, or rebuilt from them. This affects data migration and backward compatibility.
- **Bots and duplicates.** It is unclear whether crawler or repeated clicks should be excluded. This changes what the numbers mean.
- **UI or API.** It is unclear whether a visual dashboard is expected or only an API. The product currently has no UI.
- **Interaction with link expiry (brownfield).** It is unclear whether clicks on expired links should be recorded.

## Assumptions
- "Better" means per-link analytics through a read-only REST API. No UI.
- The metrics are total clicks, daily click counts over a date range, top referrer hosts, and a coarse user-agent category (browser, bot, other).
- Each click is stored as an event in a new `click_event` table: link id, UTC timestamp, referrer host only, and user-agent category only. No IP address, no full user agent, no full referrer URL.
- The existing click counter stays and is still incremented, so current API responses don't change. Events are added alongside it with no backfill. History starts at deployment.
- Events are written synchronously in the same transaction as the counter increment, for simplicity. Async recording is called out as a scaling risk.
- Bots are classified but still counted. The API can filter them out with `excludeBots=true`.
- No unique-visitor metric, because it would need fingerprinting or cookies (personal data).
- The analytics API has the same access level as the existing API. No new auth is added.
- Retention is unbounded for this prototype, and this is recorded as a limitation.
- Clicks on expired or unknown links are not recorded, because no redirect happens.
- Schema and public API changes go through human approval as governance requires. This set of assumptions is expected to be reviewed and may be rejected at design approval.

## Acceptance criteria
1. **Event recorded on redirect.** Given an existing short code, when `GET /{code}` redirects, then exactly one `click_event` row is stored with the link id, a UTC timestamp, the referrer host (or null), and a user-agent category.
2. **Counter unchanged.** Given an existing short code with N clicks, when it is redirected once, then the existing click count returns N+1, as it does today.
3. **No click for unknown codes.** Given an unknown short code, when `GET /{code}` is called, then it returns 404 and no `click_event` row is stored.
4. **No click for expired links.** Given an expired link, when it is requested, then the existing expiry response is returned and no `click_event` row is stored.
5. **Daily series.** Given clicks on 2026-11-01 (3) and 2026-11-03 (2), when `GET /api/links/{code}/analytics?from=2026-11-01&to=2026-11-03` is called, then the response has `totalClicks=5` and a daily series `[{2026-11-01:3},{2026-11-02:0},{2026-11-03:2}]`.
6. **Top referrers.** Given clicks with referrers `https://a.com/x` (2) and `https://b.com/y` (1), when analytics are requested, then `topReferrers` lists `a.com:2` before `b.com:1`. Only hosts appear, never full URLs.
7. **Bot filtering.** Given 2 clicks from a browser user agent and 1 from a known bot user agent, when analytics are requested with `excludeBots=true`, then `totalClicks=2`. Without the flag, `totalClicks=3`.
8. **Unknown code on the analytics API.** Given an unknown code, when analytics are requested, then the response is 404 with the standard error body.
9. **Invalid date range.** Given `from` later than `to`, or an invalid date format, when analytics are requested, then the response is 400 with a validation error.
10. **No personal data stored.** Given any redirect request, when the event is stored, then no IP address, full user-agent string or full referrer URL is saved. This is checked by inspecting the schema and an integration test.
11. **Docs and tests updated.** Given the change, when the build runs, then the OpenAPI spec documents the analytics endpoint, the DB schema migration adds `click_event` with an index on `(link_id, clicked_at)`, and `./mvnw verify` passes with unit and integration tests for criteria 1–10.

## Out of scope
- Dashboard or any UI.
- Geolocation, unique visitors, cookies or fingerprinting.
- Storing raw IP addresses or full user-agent and referrer strings.
- Backfilling historical events from the existing counter.
- Async or streaming event pipelines (Kafka and similar), and pre-aggregation jobs.
- Retention or purge policies, and data export (CSV and similar).
- New authentication or authorization (Spring Security).
- Analytics across links, accounts or tenants.