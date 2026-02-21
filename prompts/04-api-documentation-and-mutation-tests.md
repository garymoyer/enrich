# Prompt 04 — API Documentation and Mutation Test Coverage

**Session date:** 2026-01-30
**Resulting commit:** `662ecb4` — *updated comments and api specification, plus updated mutation tests*

---

## Context

After the service was fully working, two gaps remained:

1. The OpenAPI/Swagger documentation was thin — several controller methods were missing `@Operation`, `@ApiResponse`, and `@Parameter` annotations.
2. The PIT mutation testing run revealed surviving mutants in `PlaidApiException` and gaps in `EnrichmentServiceTest` and `GuidGeneratorTest`, dropping the score below the 80% threshold.

## Prompt

Two clean-up tasks:

### 1. API Documentation

Improve the OpenAPI annotations across the codebase:

- Add or flesh out `@Operation(summary, description)` on every endpoint in `EnrichmentController`
- Add `@ApiResponse` annotations for all meaningful HTTP status codes (200, 400, 404, 500, 503)
- Add `@Parameter` descriptions where parameters aren't self-documenting
- Add `@Schema` annotations to request/response records where useful
- Add any missing Javadoc on `PlaidApiException` constructors/fields

### 2. Mutation Test Coverage

PIT mutation testing is failing the 80% threshold. Fix by:

- Adding tests to `EnrichmentServiceTest` for the branches that surviving mutants exercise (look at the PIT HTML report under `target/pit-reports/`)
- Expanding `GuidGeneratorTest` to cover edge-case branches
- Adding a `PlaidApiExceptionTest` to exercise all constructors and field accessors on `PlaidApiException`
- Updating the PIT Maven plugin configuration if needed (e.g. output formats, excluded classes)

All existing tests must continue to pass. Do not lower the mutation threshold below 80%.
