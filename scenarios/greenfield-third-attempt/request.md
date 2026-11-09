# Change request

Build the URL shortener from scratch in the url-shortener module (Java 21, Spring Boot, H2). Users create a short link for a long URL and are redirected when they visit the short code. Validate input URLs: http and https only, a maximum length, and reject links that point back to this service. Handle short-code collisions safely with a bounded number of retries. Count clicks on each redirect and expose the count through a stats endpoint. Rate limit link creation per client. Provide an OpenAPI spec and a database schema. Include unit tests and integration tests.
