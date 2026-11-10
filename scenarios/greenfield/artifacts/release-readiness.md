## Summary

- The URL shortener was built from one plain-English request.
- Three live runs each stopped on a real build failure.
- The third run's code was recovered and fixed by a human.
- `./mvnw -pl url-shortener verify` now passes 82 tests.

## Validation results

| Check | Result |
|---|---|
| Requirements and design | Approved by a human at each gate |
| Build and tests | 82 of 82 pass, stable across two runs |
| Concurrency | 50 parallel redirects give exactly 50 clicks |
| Policy scan | Pass, checked by hand (see `policy-scan.md`) |
| Contract | `openapi.yaml` and `schema.sql` checked by `ContractFilesTest` |

## Risks and trade-offs

- **Rate limiting is per instance.** It does not hold across instances or restarts.
- **Self-link checks match exact hosts.** A hostname that resolves to a blocked IP is not caught.
- **No authentication.** Out of scope for this request.
- **Generated code needed a human.** The runs produced a missing dependency, a non-compiling test and a constructor mismatch. Running the tests then exposed a real database bug.
- **No resume after a safe-stop.** These artifacts were written by hand.

## Limitations

- The automated pipeline did not reach a passing build alone.
- Coverage was not measured.

## Recommendation

RELEASE WITH CONDITIONS. Merge after a reviewer checks the two human fixes, the constructor overload and the H2 setting. Both were merged in PR #8.
