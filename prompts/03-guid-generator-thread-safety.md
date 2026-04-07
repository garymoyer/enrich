# Prompt 03 — GuidGenerator Thread Safety

**Session date:** 2026-01-30
**Resulting commit:** `06f00fd` — *ensure thread safety and singleton behavior of GuidGenerator*

---

## Context

`GuidGenerator` is injected as a Spring singleton and used from the batch endpoint which processes requests in parallel (via Reactor's parallel scheduler). A code review flagged that the utility class needed explicit thread-safety guarantees and singleton enforcement.

## Prompt

The `GuidGenerator` class is a Spring `@Component` singleton that generates UUID v4 values. The batch enrichment endpoint calls it concurrently from multiple threads via a reactive Flux.

Make sure `GuidGenerator`:

1. Is explicitly thread-safe — document and enforce this (e.g. `ThreadLocal`, stateless method, or clear Javadoc explaining why it is already safe)
2. Enforces singleton behaviour defensively — prevent accidental instantiation of a second instance (consider a static instance guard or similar pattern)
3. Has its existing unit tests (`GuidGeneratorTest`) updated to cover the thread-safety and singleton constraints

Do not change the public API of the class or the UUID generation algorithm.
