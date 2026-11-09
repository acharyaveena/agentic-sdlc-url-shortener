# Change request

Add link expiry to the existing URL shortener. Each link should stop redirecting after a configurable time-to-live and the service should return 410 Gone for an expired link. Expired links that are no longer useful should eventually be cleaned up.
