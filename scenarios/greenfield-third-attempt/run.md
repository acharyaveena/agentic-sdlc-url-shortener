# Run summary

| field | value |
|---|---|
| run | 475aab57-46e0-4fe1-950f-b573a492a15d |
| scenario | greenfield |
| change type | greenfield |
| status | SAFE_STOPPED |
| branch | agent/greenfield-475aab57 |
| stop reason | stage build-test failed after 1 attempt(s): build failed (exit 1): |
| rolled back to | f0a226d403 |
| failed state kept at | agent-failed/475aab57 |

## Stages

| stage | status | revision | attempts | commit |
|---|---|---|---|---|
| requirements | SUCCEEDED | 1 | 1 | ccf5271bcb |
| impact-analysis | SKIPPED | 1 | 1 | - |
| design | SUCCEEDED | 1 | 1 | f0a226d403 |
| code | SUCCEEDED | 1 | 1 | 9aab9146bf |
| tests | SUCCEEDED | 1 | 1 | d46bdfd465 |
| build-test | FAILED | 1 | 1 | - |
| docs | BLOCKED | 1 | 1 | - |
| policy-scan | BLOCKED | 1 | 1 | - |
| release-readiness | BLOCKED | 1 | 1 | - |

## Stop reason detail

```text
stage build-test failed after 1 attempt(s): build failed (exit 1):
[ERROR] COMPILATION ERROR : 
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/ratelimit/FixedWindowRateLimiterTest.java:[26,19] constructor FixedWindowRateLimiter in class com.veenaacharya.shortener.ratelimit.FixedWindowRateLimiter cannot be applied to given types;
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project url-shortener: Compilation failure
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/ratelimit/FixedWindowRateLimiterTest.java:[26,19] constructor FixedWindowRateLimiter in class com.veenaacharya.shortener.ratelimit.FixedWindowRateLimiter cannot be applied to given types;
[ERROR]   required: int,java.time.Duration,java.time.Clock
[ERROR]   found:    com.veenaacharya.shortener.config.AppProperties,com.veenaacharya.shortener.support.MutableClock
[ERROR]   reason: actual and formal argument lists differ in length
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```
