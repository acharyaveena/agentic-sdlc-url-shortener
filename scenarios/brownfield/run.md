# Run summary

| field | value |
|---|---|
| run | 23df9175-6527-459c-b9f9-f473221de25a |
| scenario | brownfield |
| change type | brownfield |
| status | SAFE_STOPPED |
| branch | agent/brownfield-23df9175 |
| stop reason | stage build-test failed after 1 attempt(s): build failed (exit 1): |
| rolled back to | ab9caa14db |
| failed state kept at | agent-failed/23df9175 |

## Stages

| stage | status | revision | attempts | commit |
|---|---|---|---|---|
| requirements | SUCCEEDED | 1 | 1 | afae34e757 |
| impact-analysis | SUCCEEDED | 1 | 1 | 712a39159d |
| design | SUCCEEDED | 3 | 1 | ab9caa14db |
| code | SUCCEEDED | 1 | 1 | 3f964abec7 |
| tests | SUCCEEDED | 1 | 1 | 117baaa268 |
| build-test | FAILED | 1 | 1 | - |
| docs | BLOCKED | 1 | 1 | - |
| policy-scan | BLOCKED | 1 | 1 | - |
| release-readiness | BLOCKED | 1 | 1 | - |

## Stop reason detail

```text
stage build-test failed after 1 attempt(s): build failed (exit 1):
[ERROR]   location: class com.veenaacharya.shortener.ApiExceptionHandler
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/ApiExceptionHandler.java:[33,60] cannot find symbol
[ERROR]   symbol:   class RateLimitExceededException
[ERROR]   location: class com.veenaacharya.shortener.ApiExceptionHandler
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/web/CreateLinkRequest.java:[3,38] package jakarta.validation.constraints does not exist
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/web/LinkController.java:[6,26] package jakarta.validation does not exist
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/ApiExceptionHandler.java:[12,23] cannot find symbol
[ERROR]   symbol:   class InvalidUrlException
[ERROR]   location: class com.veenaacharya.shortener.ApiExceptionHandler
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/ApiExceptionHandler.java:[22,23] cannot find symbol
[ERROR]   symbol:   class LinkNotFoundException
[ERROR]   location: class com.veenaacharya.shortener.ApiExceptionHandler
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/ApiExceptionHandler.java:[32,23] cannot find symbol
[ERROR]   symbol:   class RateLimitExceededException
[ERROR]   location: class com.veenaacharya.shortener.ApiExceptionHandler
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/web/CreateLinkRequest.java:[5,34] cannot find symbol
[ERROR]   symbol:   class NotBlank
[ERROR]   location: class com.veenaacharya.shortener.web.CreateLinkRequest
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/web/LinkController.java:[29,49] cannot find symbol
[ERROR]   symbol:   class Valid
[ERROR]   location: class com.veenaacharya.shortener.web.LinkController
[ERROR] /<repo>/url-shortener/src/main/java/com/veenaacharya/shortener/LinkService.java:[50,40] cannot find symbo
```
