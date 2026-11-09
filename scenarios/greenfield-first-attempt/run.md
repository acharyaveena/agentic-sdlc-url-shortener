# Run summary

| field | value |
|---|---|
| run | 2288a7b8-b839-456b-a3f2-76a16563562e |
| scenario | greenfield |
| change type | greenfield |
| status | SAFE_STOPPED |
| branch | agent/greenfield-2288a7b8 |
| stop reason | stage build-test failed after 1 attempt(s): build failed (exit 1): |
| rolled back to | 67841d0367 |
| failed state kept at | agent-failed/2288a7b8 |

## Stages

| stage | status | revision | attempts | commit |
|---|---|---|---|---|
| requirements | SUCCEEDED | 1 | 1 | 9fab3724a9 |
| impact-analysis | SKIPPED | 1 | 1 | - |
| design | SUCCEEDED | 1 | 2 | 67841d0367 |
| code | SUCCEEDED | 1 | 2 | 33b6119ad1 |
| tests | SUCCEEDED | 1 | 1 | 4345410061 |
| build-test | FAILED | 1 | 1 | - |
| docs | BLOCKED | 1 | 1 | - |
| policy-scan | BLOCKED | 1 | 1 | - |
| release-readiness | BLOCKED | 1 | 1 | - |

## Stop reason detail

```text
stage build-test failed after 1 attempt(s): build failed (exit 1):
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  1.934 s
[INFO] Finished at: 2026-11-09T16:02:42-06:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project url-shortener: Compilation failure: Compilation failure: 
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[7,32] package io.swagger.v3.oas.models does not exist
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[8,32] package io.swagger.v3.oas.models does not exist
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[9,28] package io.swagger.v3.parser does not exist
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[10,40] package io.swagger.v3.parser.core.models does not exist
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[11,40] package io.swagger.v3.parser.core.models does not exist
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[20,20] cannot find symbol
[ERROR]   symbol:   class OpenAPI
[ERROR]   location: class com.veenaacharya.shortener.api.OpenApiSpecTest
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[37,49] package PathItem does not exist
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[27,13] cannot find symbol
[ERROR]   symbol:   class ParseOptions
[ERROR]   location: class com.veenaacharya.shortener.api.OpenApiSpecTest
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/api/OpenApiSpecTest.java:[27,40] cannot find symbol
[ERROR]   symbol: 
```
