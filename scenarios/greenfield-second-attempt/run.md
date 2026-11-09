# Run summary

| field | value |
|---|---|
| run | 6a6faf0e-612e-43d0-bbbe-900275fe39e1 |
| scenario | greenfield |
| change type | greenfield |
| status | SAFE_STOPPED |
| branch | agent/greenfield-6a6faf0e |
| stop reason | stage build-test failed after 1 attempt(s): build failed (exit 1): |
| rolled back to | 704d6c5646 |
| failed state kept at | agent-failed/6a6faf0e |

## Stages

| stage | status | revision | attempts | commit |
|---|---|---|---|---|
| requirements | SUCCEEDED | 1 | 1 | 15955b52ff |
| impact-analysis | SKIPPED | 1 | 1 | - |
| design | SUCCEEDED | 1 | 1 | 704d6c5646 |
| code | SUCCEEDED | 1 | 1 | 178bb79c25 |
| tests | SUCCEEDED | 1 | 1 | c6d15a4235 |
| build-test | FAILED | 1 | 1 | - |
| docs | BLOCKED | 1 | 1 | - |
| policy-scan | BLOCKED | 1 | 1 | - |
| release-readiness | BLOCKED | 1 | 1 | - |

## Stop reason detail

```text
stage build-test failed after 1 attempt(s): build failed (exit 1):
[ERROR] COMPILATION ERROR : 
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/web/LinkApiIntegrationTest.java:[66,71] cannot find symbol
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project url-shortener: Compilation failure
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/web/LinkApiIntegrationTest.java:[66,71] cannot find symbol
[ERROR]   symbol:   method doesNotExist()
[ERROR]   location: interface org.springframework.test.web.servlet.ResultMatcher
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```
