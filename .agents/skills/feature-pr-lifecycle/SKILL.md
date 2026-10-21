---
name: feature-pr-lifecycle
description: >-
  Use this skill when creating, verifying, creating PRs, monitoring CI checks,
  and squash-merging feature branches to main using GitHub CLI (gh).
---

# Feature PR Lifecycle & Quality Gate Runbook

Enforces the inner-to-outer loop pre-commit quality gate, conventional commit hygiene, and automated GitHub CLI (`gh`) PR management.

---

## 1. Pre-Commit Quality Gate (Ordered: Semantics & Security First)

Never format code that still has failing tests or static defects:

1. **Verify Static Defects & Unit Tests**:
   ```bash
   ./gradlew spotbugsMain test -q --console=plain
   ```
2. **Module-Specific Integration Gate (if applicable)**:
   - If `wiremock-extension-casual` was touched:
     ```bash
     ./gradlew :wiremock-extension-casual:quarkusIntegrationTest -q --console=plain
     ```
   - If `wiremock-extension-ui` was touched:
     ```bash
     ./gradlew :wiremock-extension-ui:test --tests "UiAssetAndContractTest" --tests "UiModuleUnitTest" -q --console=plain
     ```
3. **Format & Final Outer Gate**:
   ```bash
   ./gradlew spotlessApply check -q --console=plain
   ```

---

## 2. Commit & Push Hygiene

1. **Conventional Commits**:
   - Format: `feat(scope): concise title`, `fix(scope): ...`, `docs(scope): ...`, `refactor(scope): ...`, `test(scope): ...`, `chore(scope): ...`
   - Scopes: `cli`, `ui`, `casual`, `netem`, `stateful`, `openapi`, `otel`, `logging`, `bundle`, `examples`, `build`, `workflow`
2. **Inviolable Invariant**:
   - **NEVER** edit or view `CHANGELOG.md` or `gradle.properties` version (managed automatically by `release-please`).
3. **Push to Remote**:
   ```bash
   git push -u origin <branch-name>
   ```

---

## 3. PR Creation via GitHub CLI (`gh`)

Create the PR with structured markdown:
```bash
gh pr create \
  --base main \
  --title "feat(scope): terse summary" \
  --body "## Summary
Brief description of feature and motivation.

## Key Changes
- Itemized changes across modules.

## Verification
- Commands run during pre-commit gate."
```

---

## 4. CI Monitoring & Status Verification

1. **Watch CI Checks**:
   ```bash
   gh pr checks --watch
   ```
2. **If CI Fails**:
   - Inspect failed run:
     ```bash
     gh run list --limit 3
     gh run view <run_id> --log-failed
     ```
   - Fix locally, run inner loop, commit, and push.

---

## 5. Squash Merge & Local Sync

Once checks pass and changes are approved:
1. **Squash Merge**:
   ```bash
   gh pr merge <pr_number_or_branch> --squash --delete-branch
   ```
2. **Sync Local Workspace**:
   ```bash
   git checkout main
   git pull origin main
   ```
