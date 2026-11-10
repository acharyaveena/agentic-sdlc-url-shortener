# Run summary

| field | value |
|---|---|
| run | 21bf5f2f-27c8-4f1e-b64b-e0638009a26d |
| scenario | ambiguous-analytics |
| change type | brownfield |
| status | SAFE_STOPPED |
| branch | agent/ambiguous-analytics-21bf5f2f |
| stop reason | stage build-test failed after 1 attempt(s): build failed (exit 1): |
| rolled back to | 4508a127ad |
| failed state kept at | agent-failed/21bf5f2f |

## Stages

| stage | status | revision | attempts | commit |
|---|---|---|---|---|
| requirements | SUCCEEDED | 1 | 1 | 86aace33fc |
| impact-analysis | SUCCEEDED | 2 | 1 | a51e5ecb55 |
| design | SUCCEEDED | 3 | 1 | 4508a127ad |
| code | SUCCEEDED | 1 | 1 | 09ac4f6297 |
| tests | SUCCEEDED | 1 | 1 | 81a702954f |
| build-test | FAILED | 1 | 1 | - |
| docs | BLOCKED | 1 | 1 | - |
| policy-scan | BLOCKED | 1 | 1 | - |
| release-readiness | BLOCKED | 1 | 1 | - |

## Stop reason detail

```text
stage build-test failed after 1 attempt(s): build failed (exit 1):
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/link/LinkServiceClickRecordingTest.java:[112,74] cannot find symbol
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project url-shortener: Compilation failure: Compilation failure: 
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/link/LinkServiceClickRecordingTest.java:[39,39] cannot find symbol
[ERROR]   symbol:   class ClickMetadata
[ERROR]   location: class com.veenaacharya.shortener.link.LinkServiceClickRecordingTest
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/link/LinkServiceClickRecordingTest.java:[55,39] cannot find symbol
[ERROR]   symbol:   class ClickMetadata
[ERROR]   location: class com.veenaacharya.shortener.link.LinkServiceClickRecordingTest
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/link/LinkServiceClickRecordingTest.java:[68,39] cannot find symbol
[ERROR]   symbol:   class ClickMetadata
[ERROR]   location: class com.veenaacharya.shortener.link.LinkServiceClickRecordingTest
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/link/LinkServiceClickRecordingTest.java:[90,9] cannot find symbol
[ERROR]   symbol:   class ClickMetadata
[ERROR]   location: class com.veenaacharya.shortener.link.LinkServiceClickRecordingTest
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/link/LinkServiceClickRecordingTest.java:[90,30] cannot find symbol
[ERROR]   symbol:   variable ClickMetadata
[ERROR]   location: class com.veenaacharya.shortener.link.LinkServiceClickRecordingTest
[ERROR] /<repo>/url-shortener/src/test/java/com/veenaacharya/shortener/link/LinkServiceClickRecordingTest.java:[99,81] cannot find symbol
[ERROR]   symbol:   class ClickMetadata
[ERROR]   location: class com.veenaacharya.shortener.link.LinkServiceClickRecordingTest
[ERROR] 
```
