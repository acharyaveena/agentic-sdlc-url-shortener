## Design overview
Layered Spring Boot service: controller, service, repository. (mock output)

## Task plan
| id | task | depends on | stage |
|----|------|-----------|-------|
| T1 | Link entity and repository | - | code |
| T2 | Create and redirect endpoints | T1 | code |
| T3 | Unit and integration tests | T1 | tests |

## API changes
```yaml
paths:
  /api/v1/links:
    post:
      summary: Create a short link
```

## Schema changes
None

## Approvals needed
- None; no public API or schema change beyond the initial service.
