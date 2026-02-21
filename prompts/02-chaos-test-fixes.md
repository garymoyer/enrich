# Prompt 02 — Chaos Test Fixes

**Session date:** 2026-01-30
**Resulting commit:** `ce93ffb` — *updates after running chaos test*

---

## Context

After running the chaos test suite (`PlaidApiClientChaosTest`) the tests were failing. The issues were traced to:

- Stray `@EnableRetry` / redundant retry annotations conflicting with the Resilience4j configuration
- Unnecessary `@SpringBootApplication(scanBasePackages = ...)` override in `EnrichServiceApplication` causing component scan issues
- The `ResilienceConfig` having an annotation conflict

## Prompt

I ran `mvn test -Dtest=PlaidApiClientChaosTest` and the chaos tests are failing. Fix the issues so all chaos tests pass. The specific problems appear to be:

1. Remove the redundant Spring Retry dependency and `@EnableRetry` — Resilience4j handles all retry logic
2. Clean up `EnrichServiceApplication` — remove the explicit `scanBasePackages` override, it isn't needed and is causing conflicts
3. Fix `ResilienceConfig` — remove conflicting annotations that duplicate what the Resilience4j starter already provides

Do not change the test logic itself or the resilience configuration values — just fix the wiring so the existing chaos tests pass cleanly.
