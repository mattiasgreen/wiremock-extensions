# AGENTS.md: `wiremock-extension-stateful` Module Guidelines

Scoped instructions for `wiremock-extension-stateful` (In-memory dynamic stateful API simulation, AST condition/action evaluation, and multi-tenant session isolation).

---

## 1. Fast Verification Loops

- **Targeted Test Class (< 3s)**:
  ```bash
  ./gradlew :wiremock-extension-stateful:test --tests "StateEngineTest" -q --console=plain
  ```
- **Full Module Suite (< 6s)**:
  ```bash
  ./gradlew :wiremock-extension-stateful:test -q --console=plain
  ```

---

## 2. Engine & AST Invariants

1. **Multi-Tenant Correlation Isolation**: State mutations in `StateEngine` must always be scoped by correlation context (`x-correlation-id` / `x-tenant-id`) so parallel test executions never collide on shared state machines.
2. **Immutable AST Definitions**: `Condition`, `Action`, and `DynamicStubDefinition` models in `com.github.mattiasgreen.wiremock.stateful.dsl` must remain immutable and thread-safe; mutable runtime state lives strictly inside `StateEngine` session stores.
3. **Admin API Contract**: `StatefulAdminEndpoint` exposes `/__admin/stateful/stubs` and `/__admin/stateful/state` consumed by `wiremock-extension-ui` (`modules/dynamic-stubs.js`). Preserve JSON field compatibility when evolving AST nodes.
