# Changelog

All notable changes to the `wiremock-extensions` suite are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.8.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.7.0...v1.8.0) (2026-10-31)


### Features

* architecture deepening across casual gateway, ui modules, and cli proxy ([#61](https://github.com/mattiasgreen/wiremock-extensions/issues/61)) ([96008c0](https://github.com/mattiasgreen/wiremock-extensions/commit/96008c027ec374801e70ce7f6506169d4930e6e6))
* **casual:** standalone CasualClient CLI, clientJar, and in-browser Casual tester ([#60](https://github.com/mattiasgreen/wiremock-extensions/issues/60)) ([0c61dcc](https://github.com/mattiasgreen/wiremock-extensions/commit/0c61dcc53cfdeba476e29d8bd0c1a7c11f613ae6))
* **ui, casual, bundle:** port config simplification, enhanced snapshotting, and casual unmatched logging ([#57](https://github.com/mattiasgreen/wiremock-extensions/issues/57)) ([334bcb8](https://github.com/mattiasgreen/wiremock-extensions/commit/334bcb805c4240aa56972b71ff9fb95652da4c2b))

## [1.7.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.6.0...v1.7.0) (2026-10-26)


### Features

* **stateful:** mark extension as experimental alpha and document crossing line guidance ([#53](https://github.com/mattiasgreen/wiremock-extensions/issues/53)) ([d2f5316](https://github.com/mattiasgreen/wiremock-extensions/commit/d2f53163225b673be1d0f0ee4d361c46313c2508))
* **ui, casual:** snapshot stubs exact body matching, raw JSON editor, and fielded buffer mediation ([#55](https://github.com/mattiasgreen/wiremock-extensions/issues/55)) ([3fd0de9](https://github.com/mattiasgreen/wiremock-extensions/commit/3fd0de935be040abfea0289ddcf6976e1441d709))

## [1.6.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.5.1...v1.6.0) (2026-10-24)


### Features

* **casual:** integrate direct upstream git repositories workflow and sync tooling ([#47](https://github.com/mattiasgreen/wiremock-extensions/issues/47)) ([901fce4](https://github.com/mattiasgreen/wiremock-extensions/commit/901fce42b383304c1e82996e799a0a2e476a1a36))
* **casual:** stub-driven dynamic proxying, multi-upstream 2PC, and human-readable buffer mediation ([#49](https://github.com/mattiasgreen/wiremock-extensions/issues/49)) ([a1be1b8](https://github.com/mattiasgreen/wiremock-extensions/commit/a1be1b85f64072143b252594b93cf67787910918))

## [1.5.1](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.5.0...v1.5.1) (2026-10-24)


### Bug Fixes

* **casual:** downgrade to casual 3.4.0 for WildFly backward compatibility ([#44](https://github.com/mattiasgreen/wiremock-extensions/issues/44)) ([953975c](https://github.com/mattiasgreen/wiremock-extensions/commit/953975ca1021b377cc93b29e7649e91603679034))

## [1.5.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.4.1...v1.5.0) (2026-10-23)


### Features

* **casual:** upstream protocol engine refactor and dynamic version negotiation ([#43](https://github.com/mattiasgreen/wiremock-extensions/issues/43)) ([05c5001](https://github.com/mattiasgreen/wiremock-extensions/commit/05c50011151449bed3ab5c02ea3de0df5791a303))


### Bug Fixes

* **build:** remove JGit ratchetFrom to support shallow CI clones and worktrees ([347f6a2](https://github.com/mattiasgreen/wiremock-extensions/commit/347f6a223e5958006376bd0722dcc54ddec1a278))


### Performance Improvements

* **build:** enforce deterministic UNIX line endings and tune test worker JVM GC ([8c78dda](https://github.com/mattiasgreen/wiremock-extensions/commit/8c78ddabab8e561c393aa306c5ce1f346f74437a))
* **build:** enforce deterministic UNIX line endings, enable safe Spotless ratchet, and tune test JVM GC ([968720e](https://github.com/mattiasgreen/wiremock-extensions/commit/968720e84ffd8f353ecf78e6bbdbad625562e54a))

## [1.4.1](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.4.0...v1.4.1) (2026-10-21)


### Bug Fixes

* **cli:** provide actionable connection error and document server configuration ([#38](https://github.com/mattiasgreen/wiremock-extensions/issues/38)) ([c9c1f28](https://github.com/mattiasgreen/wiremock-extensions/commit/c9c1f28bd028a16bd46fcbd8ea60ce56594615dc)), closes [#37](https://github.com/mattiasgreen/wiremock-extensions/issues/37)

## [1.4.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.3.0...v1.4.0) (2026-10-21)


### Features

* **cli:** add lightweight JDK 21 JEP 330 developer CLI and relocate console proxy ([e3a4248](https://github.com/mattiasgreen/wiremock-extensions/commit/e3a42485ea95abb6628f3ffc87621b6d32d9d9b1))
* **netem:** protocol-agnostic stream pattern and client IP matching ([2a7a2ca](https://github.com/mattiasgreen/wiremock-extensions/commit/2a7a2ca73e81194a736b8e7b3b79b596fc061d7d))
* **netem:** protocol-agnostic stream pattern and client IP matching ([3ee83a0](https://github.com/mattiasgreen/wiremock-extensions/commit/3ee83a0e488471c5545bc8c49ca4662f151cad95))
* **ui:** add journal datetime range filtering and 1-click filtered stub snapshotting ([30b2b91](https://github.com/mattiasgreen/wiremock-extensions/commit/30b2b9103764ac5c82283465cb0da269a31a1740))
* **ui:** add journal datetime range filtering and snapshotting ([cf18a5c](https://github.com/mattiasgreen/wiremock-extensions/commit/cf18a5cf99267bd6f1f0944c33b13b2e2318649c))
* **workflow:** add feature PR and release management agent skills ([#36](https://github.com/mattiasgreen/wiremock-extensions/issues/36)) ([1681efd](https://github.com/mattiasgreen/wiremock-extensions/commit/1681efd3eeac3283b8fab2195e5c22bb183004cf))


### Bug Fixes

* **ui:** isolate playwright context per test and stabilize netem empty-state wait ([dff5859](https://github.com/mattiasgreen/wiremock-extensions/commit/dff58598f8baba6ed6d97d7da96869bb9d601af0))

## [1.3.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.2.1...v1.3.0) (2026-10-18)


### Features

* **build:** improve dev environment setup ([6af0c21](https://github.com/mattiasgreen/wiremock-extensions/commit/6af0c21f1d9df248ee21c5aff0de95a32a7c6cdb))
* **build:** improve dev environment setup, corporate mirror guidance, and IDE-agnostic runtime classpath ([dd7b1a3](https://github.com/mattiasgreen/wiremock-extensions/commit/dd7b1a3a077c9afd07bf5cb42bb612c94665f585))
* **bundle:** preload StandaloneDevServer with stateful AST, templated, and Casual presets ([78e7a03](https://github.com/mattiasgreen/wiremock-extensions/commit/78e7a035fee623d159de99438a97cd7ea45af8df))
* **casual,netem:** add L7 fault injection, XA heuristic overrides, Toxiproxy L4 chaos, and multi-tier test suite ([9497346](https://github.com/mattiasgreen/wiremock-extensions/commit/949734674ae38b2df292966d93fa2fe25ec626b2))
* **casual,ui:** add in-process CasualProxyTransformer and Casual-native UI journal/stub badges ([4f3e1f6](https://github.com/mattiasgreen/wiremock-extensions/commit/4f3e1f6d796c4ad39a0edd373e0be4042bce90c1))
* **casual:** add transparent upstream proxy forwarding, match detection, and stub recording ([bb4d432](https://github.com/mattiasgreen/wiremock-extensions/commit/bb4d432d955ea314a9d7156a6969336a0ac002db))
* **casual:** full XATMI failure modes, user error codes, tx state, and sensible record defaults ([04cf135](https://github.com/mattiasgreen/wiremock-extensions/commit/04cf135b09197f1b6cedce255fd3bce8abd932f5))
* **casual:** implement in-process Netty gateway PoC and verification test suite ([e3c5d70](https://github.com/mattiasgreen/wiremock-extensions/commit/e3c5d70ebd9f8c198cca7d6256105f3a92feee6d))
* **casual:** initialize wiremock-extension-casual subproject and PLAN.md ([1ce3c3b](https://github.com/mattiasgreen/wiremock-extensions/commit/1ce3c3b00867ab4a5ff3ab622e0fa0d62c0dfcd1))
* **casual:** phase 1 - support 2PC/XA transaction forwarding and synthetic XA voting ([d5b1db2](https://github.com/mattiasgreen/wiremock-extensions/commit/d5b1db219bcac6dd954b0565a79cfcd37dbad35e))
* **casual:** record and replay upstream faults with X-Casual-Error-State ([4e33310](https://github.com/mattiasgreen/wiremock-extensions/commit/4e33310af7543cfa1c7ce1436ec85bbe41067554))
* **casual:** verify PoC with passing integration tests and clean spotbugs gate ([47bd6e7](https://github.com/mattiasgreen/wiremock-extensions/commit/47bd6e75197db1ef63b9e207af1771bc0bd233fc))
* **examples,ui:** add dual-tab banking progressive simulation Playwright test, visual snapshots, and spec alignment ([4cd3260](https://github.com/mattiasgreen/wiremock-extensions/commit/4cd3260dbd682b20137176cef79ecf2fa8796696))
* **examples,ui:** add headless Playwright WebM demo video tour and recordDemoVideos task ([64cfe59](https://github.com/mattiasgreen/wiremock-extensions/commit/64cfe59f0ab6f524f745f2cb7475791096b1dd11))
* **examples:** add 3-node banking progressive simulation harness and headless JUnit E2E test ([5b5df4f](https://github.com/mattiasgreen/wiremock-extensions/commit/5b5df4f53199ab901d9848ca61a1d25a47d0242a))
* **examples:** add dynamic product catalog, trace correlation, and chaos simulation to banking scenario ([1e955b6](https://github.com/mattiasgreen/wiremock-extensions/commit/1e955b63b0a8b1bb60eb01018e8bb1d62750d290))
* **otel:** add stubbed vs proxied traffic taxonomy, protocol dimensions, and runtime health gauges ([fd01f9b](https://github.com/mattiasgreen/wiremock-extensions/commit/fd01f9bae3e33a0c06dad76eebb56749d7b0effc))
* **otel:** add stubbed vs proxied traffic taxonomy, protocol dimensions, and runtime health gauges ([de4845b](https://github.com/mattiasgreen/wiremock-extensions/commit/de4845b5b48cc40701b5f7751514c02381170c91))
* **ui,examples,casual:** Multi-Protocol Progressive Simulation (HTTP + Casual 2PC) ([3c48ade](https://github.com/mattiasgreen/wiremock-extensions/commit/3c48adecf647df963e3885696cce6bb053b5d53a))
* **ui:** add deep free-text search with boolean AND/OR filtering for stubs and journal ([c3f01eb](https://github.com/mattiasgreen/wiremock-extensions/commit/c3f01ebff90eb69e7eedd0bf02ec1e6af07374d1))
* **ui:** add graceful degradation and visual tooltip hints for vanilla WireMock ([834a338](https://github.com/mattiasgreen/wiremock-extensions/commit/834a338482bd7f2c05576a4b46aeee7593bd5988))
* **ui:** add multi-tab uri identification and distinguish stubbed vs proxied journal resolutions ([0eb92ef](https://github.com/mattiasgreen/wiremock-extensions/commit/0eb92ef893756a2b4606a01b703227307080bdf9))
* **ui:** add page title to indicate remote wiremock, show if stubs are mocked or proxied ([45bf7c9](https://github.com/mattiasgreen/wiremock-extensions/commit/45bf7c942b48e80a6b6c5f30bb3cb179f9e3d589))
* **ui:** add server-side journal filtering with boolean AND/OR query evaluation ([13159d9](https://github.com/mattiasgreen/wiremock-extensions/commit/13159d9a5b0238be59ffe9f534a18f01e3eb99d9))
* **ui:** implement 4-verdict journal taxonomy, unmatched triage, proxy route preset, and Java stubFor() copy ([99817a8](https://github.com/mattiasgreen/wiremock-extensions/commit/99817a8cab1bac2b4ba3ec42e6f501862eef2bef))
* **ui:** include target url and proxy strategy in WireMockConsoleProxy startup URI ([04db186](https://github.com/mattiasgreen/wiremock-extensions/commit/04db186424a3878fe44a5c5fa7832c3f6d95fd47))
* **ui:** update Copy as cURL to generate stub creation command with EOF heredoc ([18a821a](https://github.com/mattiasgreen/wiremock-extensions/commit/18a821a5cd6d54cad86dd583387ccd623c3a1a24))


### Bug Fixes

* **ui:** improved journal filtering and viewing ([404e028](https://github.com/mattiasgreen/wiremock-extensions/commit/404e028122da734e99dcb3d4a4a5a5137522262d))
* **ui:** journal high-volume lazy progressive rendering and server-side unmatched filter ([a91dfd4](https://github.com/mattiasgreen/wiremock-extensions/commit/a91dfd4317a421cb2dd1eb82206281371a1f3f7c))
* **ui:** update degradation test assertions and refine journal count display ([462eae3](https://github.com/mattiasgreen/wiremock-extensions/commit/462eae3ec46d3244870804d445e3d37d78037746))


### Performance Improvements

* **workflow:** optimize Gradle/Playwright inner-outer loops and modularize agent instructions ([d08b4e3](https://github.com/mattiasgreen/wiremock-extensions/commit/d08b4e3667761b8aa79adc322d154b799fc4bc63))

## [1.2.1](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.2.0...v1.2.1) (2026-10-13)


### Bug Fixes

* **tcp-netem:** escape ampersand in NetEmCircularJournal javadoc and enforce javadoc in check ([0102a4a](https://github.com/mattiasgreen/wiremock-extensions/commit/0102a4a6085876a3f1842b40bdba4e48892d59a7))

## [1.2.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.1.0...v1.2.0) (2026-10-12)


### Features

* add launchPrebuilt task and standalone launch.gradle script ([afede78](https://github.com/mattiasgreen/wiremock-extensions/commit/afede785990a95deb992dbec0f41c9127c0f5edb))
* implement in-process TCP network emulator and fault injector (wiremock-extension-tcp-netem) ([f066598](https://github.com/mattiasgreen/wiremock-extensions/commit/f0665980bca5d74268b159e08f1b5ac26d8caee2))
* **stateful:** add AST-driven stateful API simulation extension and client DSL ([4c9aa2c](https://github.com/mattiasgreen/wiremock-extensions/commit/4c9aa2c8c356db0dae9802dc8b2627dee6c4347d))
* **tcp-netem:** complete dual-ingress L4 network emulator, circular journal, declarative metadata, and web UI console ([e0574d9](https://github.com/mattiasgreen/wiremock-extensions/commit/e0574d983186a359ced67b0b43968948d1acaf80))
* **ui:** add remote capability detection and graceful degradation for vanilla WireMock ([b00fc9d](https://github.com/mattiasgreen/wiremock-extensions/commit/b00fc9d440277f60122c659cee5f352209ebb00d))
* **ui:** add standalone headless console proxy ([e6e7321](https://github.com/mattiasgreen/wiremock-extensions/commit/e6e732163aa9f632c64ebabb7640cbe6a169f98a))
* **ui:** add standalone headless console proxy and configurable remote targets ([782fe9a](https://github.com/mattiasgreen/wiremock-extensions/commit/782fe9aee951ebb258cf4f93d46ef06c3e85964e))
* **ui:** add stub creation templates, request body matching, and OpenAPI guidance ([877e3b4](https://github.com/mattiasgreen/wiremock-extensions/commit/877e3b440ae0f856c8679ab6119cd5b21f828e8d))
* **ui:** add stub creation templates, request body matching, and OpenAPI guidance ([245bf1e](https://github.com/mattiasgreen/wiremock-extensions/commit/245bf1e9b33d1eccbc3c6a9a8c9750c4fd33ee08))
* **ui:** integrate dynamic stateful simulation stubs, inspection, creation templates, and E2E tests ([ef915e5](https://github.com/mattiasgreen/wiremock-extensions/commit/ef915e57d636dc49aa8865e6c01b8c39cc13aa2e))
* **ui:** support relative static assets and configurable WireMock target in URL ([3a1dd9c](https://github.com/mattiasgreen/wiremock-extensions/commit/3a1dd9ce37bdc4e2ac418103cd555eed5024f211))


### Bug Fixes

* **build:** register subprojects in nmcpAggregation dependencies ([8dfad93](https://github.com/mattiasgreen/wiremock-extensions/commit/8dfad937d0c21d7e13b8f73661eeed0e4c46dcbf))
* **tcp-netem:** resolve declarative stub toxic cleanup race condition and enshrine zero-credential policy in AGENTS.md ([e42e3b2](https://github.com/mattiasgreen/wiremock-extensions/commit/e42e3b23fd88db31457f9b5b529c2924f4b1f63f))

## [1.1.0](https://github.com/mattiasgreen/wiremock-extensions/compare/v1.0.0...v1.1.0) (2026-10-08)


### Features

* **examples:** add case management stateful reference example and scenarios DAG visualizer ([3ff84fd](https://github.com/mattiasgreen/wiremock-extensions/commit/3ff84fdf9b599e378d968d7deb31511effe44174))
* **openapi,ui:** add live transparent proxying, traffic snapshot recording, and project mode toggle ([a0c0a7f](https://github.com/mattiasgreen/wiremock-extensions/commit/a0c0a7febef778a40299ff71fd2bbc05635bb10c))
* **openapi:** Add OpenAPI 3.0/3.1 spec ingestion, stub synthesis engine, Admin API route, and embedded Web UI modal ([b38b80e](https://github.com/mattiasgreen/wiremock-extensions/commit/b38b80e13fc9de1da4f46d4405c5d4c70cf6354b))
* **openapi:** Add OpenAPI 3.0/3.1 spec ingestion, stub synthesizer, Admin API route, and UI modal ([3c38d0a](https://github.com/mattiasgreen/wiremock-extensions/commit/3c38d0a6b0948a257fb64cabb0c652c602454531))
* **openapi:** Synthesize ready-made request examples with substituted parameters and prefill HTTP Tester ([b3c0090](https://github.com/mattiasgreen/wiremock-extensions/commit/b3c00902ce321a27267aea775534aa63c9590881))
* **otel,logging:** implement W3C distributed tracing and log correlation ([bfb6e69](https://github.com/mattiasgreen/wiremock-extensions/commit/bfb6e69983769ca22753a3516fa612437f79912e))
* **otel,logging:** implement W3C distributed tracing and log correlation ([dc42695](https://github.com/mattiasgreen/wiremock-extensions/commit/dc4269560748b987b1137cd203803cbddc56ffe0))
* **quality:** enforce SpotBugs/FindSecBugs gate and fix security findings ([a0d5a4b](https://github.com/mattiasgreen/wiremock-extensions/commit/a0d5a4b049cd29874386d8be9bd57d85d6aeb6e3))
* **ui:** add filterable journal, expandable detail rows and auto-refresh ([7a213cc](https://github.com/mattiasgreen/wiremock-extensions/commit/7a213cc28b1e86f46de3139c19a168ed65eea5ab))
* **ui:** add interactive HTTP request tester with deep linking and stub testing shortcut ([f1a8dee](https://github.com/mattiasgreen/wiremock-extensions/commit/f1a8dee861db545ca308522e6fb88aa578aec3c0))
* **ui:** add JSON syntax highlighting and copy as cURL utility ([770e184](https://github.com/mattiasgreen/wiremock-extensions/commit/770e18476712b5e8e22ba888a9bc9dd742c2cef5))
* **ui:** add relative URL link to neighbor /__admin/swagger-ui/ ([361e942](https://github.com/mattiasgreen/wiremock-extensions/commit/361e9424e1d8b74447f9b1f61745200b13e24e1d))
* **ui:** add stub lifecycle, project grouping, import/export hub, and bulk actions ([c9326aa](https://github.com/mattiasgreen/wiremock-extensions/commit/c9326aaff7c9c9f9dc9b04ddf4a32a7bf093eb45))
* **ui:** add URL hash router and deep linking for tabs and stubs ([b78c294](https://github.com/mattiasgreen/wiremock-extensions/commit/b78c2941ab41fce0520d8a0f9b7f28f93908d8a7))
* **ui:** clean up stub card actions and right-align project tag ([3e7271b](https://github.com/mattiasgreen/wiremock-extensions/commit/3e7271bba7aa68adecad1d0752741d123f953015))
* **ui:** display version and git branch/commit next to brand logo ([1f21d1b](https://github.com/mattiasgreen/wiremock-extensions/commit/1f21d1ba0a9eb3cfacf3008b16c8fa5973b08b41))
* **ui:** enhance HTTP tester with dual inspection, permanent headers, presets and history ([6da3dc1](https://github.com/mattiasgreen/wiremock-extensions/commit/6da3dc15b2ba9fe88ce1330e48a658af8e3a7dab))
* **ui:** redesign http tester history into compact collapsible vertical sidebar ([385e864](https://github.com/mattiasgreen/wiremock-extensions/commit/385e86486768502385f1f230346997b00fd6e2c7))
* **ui:** redesign workbench layout with persistent stubs sidebar, bottom history dock, and E2E demo ([d23d3dc](https://github.com/mattiasgreen/wiremock-extensions/commit/d23d3dcaecb23c3da8dbe992423d710896ea0416))
* **ui:** restructure topology into domain-first navigation and scoped stubs sidebar ([7ce1fa4](https://github.com/mattiasgreen/wiremock-extensions/commit/7ce1fa41df7ad8c60d9d695723dfdf32b39f16e9))
* **ui:** support dynamic modular asset routing in UiAdminApiEndpoint ([77063f7](https://github.com/mattiasgreen/wiremock-extensions/commit/77063f73f276590b7a8db90e110c2bd4a6b6e369))
* **ui:** update complete E2E scenario with stub clearing, GET/POST testing, and unmatched journal review ([0e1eaa0](https://github.com/mattiasgreen/wiremock-extensions/commit/0e1eaa00f45d4134e525bfc2e6d32115628bd81d))


### Bug Fixes

* **bundle:** add standalone runtime dependencies for runStandalone ([3d7a020](https://github.com/mattiasgreen/wiremock-extensions/commit/3d7a020b06ccf1e865675ed2432ff25e390ffd42))
* **otel:** resolve SpotBugs singleton constructor and uncalled method findings ([6474d11](https://github.com/mattiasgreen/wiremock-extensions/commit/6474d11027a0b43ce7de204dbc9d5d07e60d1636))
* **ui:** add parent relative path candidate for dev static file hot reload ([6bf1bd2](https://github.com/mattiasgreen/wiremock-extensions/commit/6bf1bd26df3ecf90d3e756be98360aabef98fdd6))
* **ui:** correct JSON syntax highlighting tokenization and escaping ([ef4ccd6](https://github.com/mattiasgreen/wiremock-extensions/commit/ef4ccd6adb26ecb1e8bdf455fa05230c69207e71))
* **ui:** prioritize /ui/version route before /ui/{file} ([c078dce](https://github.com/mattiasgreen/wiremock-extensions/commit/c078dce6e68b6a50e018911e7a0c88e8b64f7a8c))
* **ui:** Support urlPathTemplate and urlPathMatching in getStubUrl ([87aa97c](https://github.com/mattiasgreen/wiremock-extensions/commit/87aa97c3a3f0d05225abb8ad47b63b325fc7de8b))

## 1.0.0 (2026-10-08)


### Features

* **examples:** add case management stateful reference example and scenarios DAG visualizer ([3ff84fd](https://github.com/mattiasgreen/wiremock-extensions/commit/3ff84fdf9b599e378d968d7deb31511effe44174))
* **openapi,ui:** add live transparent proxying, traffic snapshot recording, and project mode toggle ([a0c0a7f](https://github.com/mattiasgreen/wiremock-extensions/commit/a0c0a7febef778a40299ff71fd2bbc05635bb10c))
* **openapi:** Add OpenAPI 3.0/3.1 spec ingestion, stub synthesis engine, Admin API route, and embedded Web UI modal ([b38b80e](https://github.com/mattiasgreen/wiremock-extensions/commit/b38b80e13fc9de1da4f46d4405c5d4c70cf6354b))
* **openapi:** Add OpenAPI 3.0/3.1 spec ingestion, stub synthesizer, Admin API route, and UI modal ([3c38d0a](https://github.com/mattiasgreen/wiremock-extensions/commit/3c38d0a6b0948a257fb64cabb0c652c602454531))
* **openapi:** Synthesize ready-made request examples with substituted parameters and prefill HTTP Tester ([b3c0090](https://github.com/mattiasgreen/wiremock-extensions/commit/b3c00902ce321a27267aea775534aa63c9590881))
* **otel,logging:** implement W3C distributed tracing and log correlation ([bfb6e69](https://github.com/mattiasgreen/wiremock-extensions/commit/bfb6e69983769ca22753a3516fa612437f79912e))
* **otel,logging:** implement W3C distributed tracing and log correlation ([dc42695](https://github.com/mattiasgreen/wiremock-extensions/commit/dc4269560748b987b1137cd203803cbddc56ffe0))
* **quality:** enforce SpotBugs/FindSecBugs gate and fix security findings ([a0d5a4b](https://github.com/mattiasgreen/wiremock-extensions/commit/a0d5a4b049cd29874386d8be9bd57d85d6aeb6e3))
* **ui:** add filterable journal, expandable detail rows and auto-refresh ([7a213cc](https://github.com/mattiasgreen/wiremock-extensions/commit/7a213cc28b1e86f46de3139c19a168ed65eea5ab))
* **ui:** add interactive HTTP request tester with deep linking and stub testing shortcut ([f1a8dee](https://github.com/mattiasgreen/wiremock-extensions/commit/f1a8dee861db545ca308522e6fb88aa578aec3c0))
* **ui:** add JSON syntax highlighting and copy as cURL utility ([770e184](https://github.com/mattiasgreen/wiremock-extensions/commit/770e18476712b5e8e22ba888a9bc9dd742c2cef5))
* **ui:** add relative URL link to neighbor /__admin/swagger-ui/ ([361e942](https://github.com/mattiasgreen/wiremock-extensions/commit/361e9424e1d8b74447f9b1f61745200b13e24e1d))
* **ui:** add stub lifecycle, project grouping, import/export hub, and bulk actions ([c9326aa](https://github.com/mattiasgreen/wiremock-extensions/commit/c9326aaff7c9c9f9dc9b04ddf4a32a7bf093eb45))
* **ui:** add URL hash router and deep linking for tabs and stubs ([b78c294](https://github.com/mattiasgreen/wiremock-extensions/commit/b78c2941ab41fce0520d8a0f9b7f28f93908d8a7))
* **ui:** clean up stub card actions and right-align project tag ([3e7271b](https://github.com/mattiasgreen/wiremock-extensions/commit/3e7271bba7aa68adecad1d0752741d123f953015))
* **ui:** display version and git branch/commit next to brand logo ([1f21d1b](https://github.com/mattiasgreen/wiremock-extensions/commit/1f21d1ba0a9eb3cfacf3008b16c8fa5973b08b41))
* **ui:** enhance HTTP tester with dual inspection, permanent headers, presets and history ([6da3dc1](https://github.com/mattiasgreen/wiremock-extensions/commit/6da3dc15b2ba9fe88ce1330e48a658af8e3a7dab))
* **ui:** redesign http tester history into compact collapsible vertical sidebar ([385e864](https://github.com/mattiasgreen/wiremock-extensions/commit/385e86486768502385f1f230346997b00fd6e2c7))
* **ui:** redesign workbench layout with persistent stubs sidebar, bottom history dock, and E2E demo ([d23d3dc](https://github.com/mattiasgreen/wiremock-extensions/commit/d23d3dcaecb23c3da8dbe992423d710896ea0416))
* **ui:** restructure topology into domain-first navigation and scoped stubs sidebar ([7ce1fa4](https://github.com/mattiasgreen/wiremock-extensions/commit/7ce1fa41df7ad8c60d9d695723dfdf32b39f16e9))
* **ui:** support dynamic modular asset routing in UiAdminApiEndpoint ([77063f7](https://github.com/mattiasgreen/wiremock-extensions/commit/77063f73f276590b7a8db90e110c2bd4a6b6e369))
* **ui:** update complete E2E scenario with stub clearing, GET/POST testing, and unmatched journal review ([0e1eaa0](https://github.com/mattiasgreen/wiremock-extensions/commit/0e1eaa00f45d4134e525bfc2e6d32115628bd81d))


### Bug Fixes

* **bundle:** add standalone runtime dependencies for runStandalone ([3d7a020](https://github.com/mattiasgreen/wiremock-extensions/commit/3d7a020b06ccf1e865675ed2432ff25e390ffd42))
* **otel:** resolve SpotBugs singleton constructor and uncalled method findings ([6474d11](https://github.com/mattiasgreen/wiremock-extensions/commit/6474d11027a0b43ce7de204dbc9d5d07e60d1636))
* **ui:** add parent relative path candidate for dev static file hot reload ([6bf1bd2](https://github.com/mattiasgreen/wiremock-extensions/commit/6bf1bd26df3ecf90d3e756be98360aabef98fdd6))
* **ui:** correct JSON syntax highlighting tokenization and escaping ([ef4ccd6](https://github.com/mattiasgreen/wiremock-extensions/commit/ef4ccd6adb26ecb1e8bdf455fa05230c69207e71))
* **ui:** prioritize /ui/version route before /ui/{file} ([c078dce](https://github.com/mattiasgreen/wiremock-extensions/commit/c078dce6e68b6a50e018911e7a0c88e8b64f7a8c))
* **ui:** Support urlPathTemplate and urlPathMatching in getStubUrl ([87aa97c](https://github.com/mattiasgreen/wiremock-extensions/commit/87aa97c3a3f0d05225abb8ad47b63b325fc7de8b))

## [Upcoming / In-Flight on Branches]

### Branch `feat/otel-tracing-and-logging`
*Tracking branch for distributed tracing and observability integrations.*

- **`[2026-10-05 05:02:02 +02:00]`** (`dc42695`) **W3C Distributed Tracing (`wiremock-extension-otel`)**:
  - `OtelTracingListener` (`ServeEventListener`): Intercepts all incoming WireMock serve events and starts in-process OpenTelemetry `SERVER` spans.
  - `WireMockRequestTextMapGetter`: Extracts W3C `traceparent` and `tracestate` headers using OpenTelemetry context propagation.
  - Standard OTel semantic conventions: Populates spans with `http.request.method`, `http.response.status_code`, `url.path`, `wiremock.matched`, and `wiremock.stub_name`.
  - Error span tagging: Automatically marks spans as `StatusCode.ERROR` on HTTP 5xx responses or unmatched requests.
  - `OtelTracingRegistry`: Configurable tracer provider with testable in-memory span exporters.
  - `OtelTracingTest`: Comprehensive unit and integration tests verifying context propagation across parent-child spans.
- **`[2026-10-05 05:02:02 +02:00]`** (`dc42695`) **MDC Log-Trace Correlation (`wiremock-extension-json-logging`)**:
  - `MdcRequestFilter` & `JsonLoggingListener`: Injects active OTel `trace_id` and `span_id` directly into SLF4J MDC.
  - `WireMockLogEvent`: Serializes `trace_id` and `span_id` fields into single-line JSON logs for instant log-to-trace correlation in Datadog, Grafana Loki, or Elasticsearch.
  - `MdcAndJsonLoggingTest`: Automated verification of MDC key propagation under traced requests.
- **`[2026-10-05 05:02:02 +02:00]`** (`dc42695`) **Header Normalization Performance**:
  - `HeaderNormalizer`: Precompiles regex `Pattern` constants for header sanitization instead of recompiling on every request.

---

### Branch `feat/openapi-proxy-recording`
*Tracking branch for transparent proxying, traffic recording, and project workflows.*

- **`[2026-10-05 06:52:53 +02:00]`** (`17122a6`) **Roadmap & Wishlist Documentation**:
  - Added configurable outbound HTTPS certificate validation and dynamic downstream certificate forging specifications to `README.md`.
- **`[2026-10-05 05:58:28 +02:00]`** (`163871e`) **Live Transparent Proxying & Traffic Recording (`wiremock-extension-openapi`)**:
  - `GenerationMode` enum: Configurable stub generation modes (`MOCK_RESPONSES`, `LIVE_PROXY`, `RECORD_TRAFFIC`).
  - Upstream proxy routing in `OpenApiStubGenerator`: Automatically configures `proxyBaseUrl` stubs based on OpenAPI `servers` configuration or user input.
  - `OpenApiSpecInspector` & `OpenApiServerInfo`: Parses specification metadata, servers, and base paths for live target selection.
  - Transparent traffic recording: Records live upstream traffic responses into WireMock stub mappings.
- **`[2026-10-05 05:58:28 +02:00]`** (`163871e`) **Stub Lifecycle & Project Mode UI (`wiremock-extension-ui`)**:
  - `StubLifecycleAdminEndpoint`: Dedicated REST endpoint (`/__admin/lifecycle/...`) for project-scoped stub management and promotion.
  - Project workspace switcher (`modules/projects.js`): Toggle between OpenAPI projects and execution modes (Mock vs. Proxy vs. Recording).
  - Request Journal Promotion (`modules/journal.js`): Single-click "Promote to Stub" button converting live proxied transactions directly into permanent mappings.
  - Visual mode indicators in navigation toolbar and stubs list.
---

## [Committed on `main`]

### [0.1.0] - 2026-10-05

#### Added / Enhanced
- **`[2026-10-05 08:16:00 +02:00]`** **CI / CD Pipeline Modernization (`.github/workflows/ci.yml`)**:
  - Upgraded to `actions/setup-java@v5` and `gradle/actions/setup-gradle@v4` with automatic dependency and build caching.
  - Added automated `spotlessCheck` step to fail fast on formatting regressions.
  - Maintained 100% Zero-NPM pure Java and Vanilla JS pipeline execution.
- **`[2026-10-05 08:16:00 +02:00]`** **Packaging & Distribution Refinements (`build.gradle`, `README.md`)**:
  - Configured `maven-publish` to selectively publish distributable extensions and exclude internal `wiremock-examples`.
  - Added Zero-Build Consumer Runner guide using published Gradle dependencies.
  - Added production-ready Alpine Dockerfile reference architecture.
- **`[2026-10-05 04:11:11 +02:00]`** (`0e1eaa0`) **E2E Scenario & Demo Recording**:
  - Updated complete E2E scenario in `UiPlaywrightTest` and `UiDemoGifRecordingTest` covering stub clearing, GET/POST execution, and unmatched journal review.
  - Regenerated all demonstration GIFs for documentation.
- **`[2026-10-04 17:08:24 +02:00]`** (`385e864`) **Compact Tester Execution History**:
  - Redesigned HTTP tester history into a compact collapsible vertical sidebar with status badges, method tags, and latencies.
- **`[2026-10-04 15:46:26 +02:00]`** (`3e7271b`) **Stub Card Actions Cleanup**:
  - Polished stub card action buttons and right-aligned project badge tags.
- **`[2026-10-04 08:10:37 +02:00]`** (`d23d3dc`) **Workbench Layout Redesign**:
  - Redesigned console workbench layout featuring a persistent Stubs Explorer sidebar, top Request/Response split view, and bottom history dock.
- **`[2026-10-04 06:08:11 +02:00]`** (`c9326aa`) **Stub Portfolio Management & Bulk Hub**:
  - Added stub lifecycle states (`draft`, `active`, `deprecated`), project grouping, bulk actions, and bundle import/export hub.
- **`[2026-10-04 05:38:50 +02:00]`** (`b38b80e`) **Merge pull request #1**:
  - Merged feature branch `feat/openapi-importer` into `main`.
- **`[2026-10-04 05:31:04 +02:00]`** (`b3c0090`) **Ready-Made Request Payloads**:
  - Synthesized format-aware JSON request bodies with substituted path parameters in `wiremock-extension-openapi` for one-click tester prefill.
- **`[2026-10-04 05:17:47 +02:00]`** (`87aa97c`) **URL Matcher Path Resolution**:
  - Extended UI URL resolution (`getStubUrl`) to support `urlPathTemplate` and `urlPathMatching`.
- **`[2026-10-04 04:47:05 +02:00]`** (`3c38d0a`) **OpenAPI 3.0/3.1 Ingestion & Stub Synthesis**:
  - Created `wiremock-extension-openapi` subproject with OpenAPI 3.0/3.1 YAML/JSON parsing, schema dereferencing, `SchemaDataSynthesizer`, Admin API route (`POST /__admin/openapi/import`), and UI import modal.
- **`[2026-10-03 06:44:39 +02:00]`** (`1e4afcd`) **Roadmap Documentation**:
  - Updated `TODO.md` with enterprise readiness tasks and added product `ROADMAP.md`.
- **`[2026-10-03 06:30:13 +02:00]`** (`4cfcbdd`) **Documentation Maintenance**:
  - Updated `TODO.md` with completed Phase 1 and Phase 2 items.
- **`[2026-10-03 06:29:31 +02:00]`** (`c8eb686`) **Native ES-Module Decomposition**:
  - Decomposed monolithic `app.js` into modular native ES modules (`dom.js`, `highlighter.js`, `state.js`, `router.js`, `stubs.js`, `journal.js`, `tester.js`, `scenarios.js`, `api.js`) under strict Zero-NPM / Zero-Bundler constraints.
- **`[2026-10-03 06:26:17 +02:00]`** (`77063f7`) **Dynamic Modular Asset Routing**:
  - Upgraded `UiAdminApiEndpoint` to dynamically resolve module paths (`/ui/modules/*`), detect MIME types, and enforce path traversal guards.
- **`[2026-10-03 06:25:07 +02:00]`** (`d27bc49`) **Strict Compiler Flags**:
  - Configured Java compilation across subprojects with `-Xlint:all` and `-Werror`.
- **`[2026-10-03 06:23:28 +02:00]`** (`0e138d9`) **Spotless Code Formatting**:
  - Integrated Spotless with Palantir Java Format and unified formatting across Java, Gradle, Markdown, and web assets.
- **`[2026-10-03 06:21:52 +02:00]`** (`52dbbbe`) **Gradle Version Catalog**:
  - Centralized dependency management into `gradle/libs.versions.toml`.
- **`[2026-10-03 06:20:34 +02:00]`** (`2853f2c`) **Agent Guidelines (`AGENTS.md`)**:
  - Added comprehensive `AGENTS.md` specifying repository conventions, constraints, and architecture.
- **`[2026-10-03 06:20:13 +02:00]`** (`b5a8a95`) **Demo GIF Recording Isolation**:
  - Isolated heavy GIF generation tests behind Jupiter `@Tag("recording")` and dedicated `recordDemoGifs` task.
- **`[2026-10-03 06:10:13 +02:00]`** (`c013ddc`) **Architecture Review & Scorecard**:
  - Created initial executive scorecard and prioritized phased roadmap in `TODO.md`.
- **`[2026-10-03 06:10:05 +02:00]`** (`3ff84fd`) **Stateful Scenarios & DAG Visualizer**:
  - Implemented interactive SVG Scenario DAG visualizer in UI and created `wiremock-examples` Case Management reference implementation.
- **`[2026-10-03 05:46:35 +02:00]`** (`4f22aa6`) **Automated Demo Walkthroughs**:
  - Added automated Playwright demo GIF recording tests and embedded walkthrough animations in READMEs.
- **`[2026-10-03 04:35:04 +02:00]`** (`7ce1fa4`) **Domain-First UI Topology**:
  - Restructured UI navigation layout into domain tabs with scoped stubs sidebar.
- **`[2026-10-03 04:20:40 +02:00]`** (`f5b8e33`) **Subproject Documentation Guides**:
  - Restructured README documentation into dedicated modular guides for all subprojects.
- **`[2026-10-03 04:09:05 +02:00]`** (`ef4ccd6`) **Syntax Highlighting Fix**:
  - Corrected JSON syntax highlighting tokenization and escaping in `highlighter.js`.
- **`[2026-10-02 08:28:34 +02:00]`** (`361e942`) **Swagger UI Link**:
  - Added relative navigation link to neighbor `/__admin/swagger-ui/`.
- **`[2026-10-02 08:12:23 +02:00]`** (`6da3dc1`) **HTTP Tester Enhancements**:
  - Added dual inspection view, permanent headers, presets (`+ JSON`, `+ Bearer`), and execution history dock.
- **`[2026-10-02 07:59:15 +02:00]`** (`6bf1bd2`) **Hot-Reload Dev Static Files**:
  - Added parent relative candidate path checking for development static file hot reloading.
- **`[2026-10-02 07:58:36 +02:00]`** (`b050b45`) **Playwright UI Testing**:
  - Migrated UI test suite to Playwright headless Chromium and enabled live file hot-reload testing.
- **`[2026-10-02 06:59:14 +02:00]`** (`dd5112f`) **UI Test Coverage Expansion**:
  - Added test coverage for tester inputs, journal search filtering, and tab routing.
- **`[2026-10-02 06:47:35 +02:00]`** (`f1a8dee`) **Interactive HTTP Tester**:
  - Added built-in HTTP client in the web UI with URL hash deep-linking and **⚡ Test Stub** shortcut.
- **`[2026-10-02 06:45:36 +02:00]`** (`770e184`) **JSON Syntax Highlighting & cURL Export**:
  - Added client-side JSON syntax coloring and one-click copy as `curl` utility.
- **`[2026-10-02 06:44:03 +02:00]`** (`7a213cc`) **Filterable Request Journal**:
  - Added journal table viewer with match status badges, expandable detail rows, and auto-refresh.
- **`[2026-10-02 06:42:33 +02:00]`** (`b78c294`) **URL Hash Router & Deep Linking**:
  - Implemented client-side hash router (`#stubs`, `#journal`, `#tester`, `#scenarios`) with bookmarkable query parameters.
- **`[2026-10-02 06:26:44 +02:00]`** (`3d7a020`) **Standalone Runner Dependencies**:
  - Configured standalone runtime dependencies for the `runStandalone` task in `wiremock-extension-bundle`.
- **`[2026-10-02 06:08:13 +02:00]`** (`920c1bf`) **Initial Commit: Modular WireMock Extensions Suite**:
  - Initialized repository with `wiremock-extension-json-logging`, `wiremock-extension-otel`, `wiremock-extension-ui`, and `wiremock-extension-bundle`.
