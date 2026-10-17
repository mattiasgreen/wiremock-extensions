---
name: demo-video-recording
description: >-
  Use this skill when generating or updating animated demo GIFs, pitch deck screenshots,
  or headless Playwright WebM video tours in wiremock-extension-ui.
---

# Demo Video, GIF & Pitch Deck Recording Runbook

Recording tests are tagged with `@Tag("recording")` or `@Tag("pitch-deck")` and are **excluded from standard `./gradlew test`** so daily inner/outer loops stay fast and non-mutating.

## 1. Available Recording Tasks (`wiremock-extension-ui`)

| Task | JUnit Tag / Test Class | Output Location |
| :--- | :--- | :--- |
| `./gradlew :wiremock-extension-ui:captureUiSnapshots` | `@Tag("ui-snapshot")` (`UiSnapshotCaptureTest`) | `wiremock-extension-ui/build/reports/screenshots/web/` |
| `./gradlew :wiremock-extension-ui:recordDemoVideos` | `@Tag("recording")` (`UiVideoRecordingTest`) | `wiremock-extension-ui/build/videos/*.webm` |
| `./gradlew :wiremock-extension-ui:recordDemoGifs` | `@Tag("recording")` (`UiDemoGifRecordingTest`) | `docs/images/demo-*.gif` |
| `./gradlew :wiremock-extension-ui:recordPitchDeckScreenshots` | `@Tag("pitch-deck")` (`UiPitchDeckScreenshotsTest`) | `docs/site/assets/screenshot-*.png` |

## 2. Asset Hygiene, Frame-0 Presentation & Agent-Gated Workflow
- **Never place marketing GIFs, WebM videos, or PNG screenshots inside `wiremock-extension-ui/src/main/resources/ui/`**: Doing so bloats the published Maven Central JAR by megabytes. Keep all documentation visuals under `docs/` or `build/`.
- **WebM Video Tours (Phase 10 Pipeline)**:
  - **Frame-0 Start**: Start recordings directly inside `/__admin/ui/` at frame 0 (never bake title slides or HUD overlays into video pixels; keep story titles and `Given / When / Then` steps in the HTML/Markdown above `<video>`).
  - **Agent-Gated Local Execution**: Never run video generation in GitHub Actions. During PR/release prep, inspect `git log` / `git diff` on `wiremock-extension-ui/src/main/resources/ui/**` to judge if user-visible flows changed, and ask the user for confirmation before running `recordDemoVideos` locally.
  - **Zero-Bloat Hosting**: Never commit binary `.webm` files to `main` Git history; publish to GitHub Pages / Release assets after local inspection and user approval.
