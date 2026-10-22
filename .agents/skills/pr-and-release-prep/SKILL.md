---
name: pr-and-release-prep
description: >-
  Use this skill before committing, pushing, preparing a Pull Request (PR), or verifying
  release readiness for wiremock-extensions. Covers the Outer Loop quality gate,
  release-please conventions, demo site synchronization, and PR summary generation.
---

# Pull Request (PR) & Release Preparation Runbook

## 1. Pre-Commit Quality Gate
Always prioritize defects and security before formatting polish:
1. **Verify Static Defects & Unit Tests First**:
   ```bash
   ./gradlew spotbugsMain test -q --console=plain
   ```
2. **Apply Formatting & Run Final Gate**:
   ```bash
   ./gradlew spotlessApply check -q --console=plain
   ```
- **If `wiremock-extension-casual` was modified**: Also run the 3-node 2PC/MitM integration suite:
  ```bash
  ./gradlew :wiremock-extension-casual:quarkusIntegrationTest -q --console=plain
  ```
- **If `wiremock-extension-ui` static assets were modified**: Verify `syncDemoSite` keeps the static demo in sync without committing duplicate files if excluded by `.gitignore`.

## 2. Release-Please & Versioning Rules
- **Conventional Commits**: Always use `feat(scope): ...`, `fix(scope): ...`, `perf(scope): ...`, `refactor(scope): ...`, `docs(scope): ...`, or `test(scope): ...`.
- **Never Hand-Edit `CHANGELOG.md` or `gradle.properties` Version**: Both are managed automatically by `release-please` (`release.yml`). Do not waste tokens viewing `CHANGELOG.md`.
- **New Extension Module Checklist**: When adding a new subproject to `settings.gradle`:
  1. Ensure it is included in `wiremock-extension-bundle/build.gradle` (if part of the standalone meta-bundle) and registered in `META-INF/services/com.github.tomakehurst.wiremock.extension.ExtensionFactory`.
  2. Root `build.gradle` automatically aggregates all subprojects (except `wiremock-examples`) into `nmcpAggregation` for Maven Central publishing.
  3. Add a scoped `AGENTS.md` inside the new module and a row in root `AGENTS.md`.

## 3. PR Summary Output Checklist
Before presenting a completed PR to the user, verify and output:
1. **Abstract Spec & Docs Alignment**: Confirm `docs/spec/functional-specification.md`, `docs/spec/platform-bindings.md`, and relevant module `README.md` files are updated.
2. **Senior Self-Review Reflection**: For UI/UX or developer-experience changes, include the 5 Lenses self-critique.
3. **Copy-Pasteable PR Markdown**: Output a clean markdown block containing `## Summary`, `## Key Changes`, and `## Verification`.
4. **Direct PR Creation URL**:
   `https://github.com/mattiasgreen/wiremock-extensions/compare/main...<branch-name>?expand=1`
