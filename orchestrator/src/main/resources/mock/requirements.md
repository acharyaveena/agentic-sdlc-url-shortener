## Problem statement
Build a URL shortener service that creates short codes for long URLs and redirects visitors. (mock output)

## Ambiguities
- Short-code length and alphabet are not specified.
- Redirect status code (301 or 302) is not specified.

## Assumptions
- Codes are 7 Base62 characters.
- Redirects use 302 so every visit is counted.

## Acceptance criteria
1. Given a valid URL, when it is submitted, then a short code is returned.
2. Given a known code, when it is visited, then the client is redirected to the original URL.
