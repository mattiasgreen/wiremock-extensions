# WireMock CLI (`wiremock-cli`)

A lightweight, zero-compile terminal observability CLI and headless proxy client for WireMock Standalone (JDK 21).

---

## 1. Problem Statement

Modern microservice, backend, and integration testing happens inside terminal windows, tmux split-panes, remote SSH bastion hosts, and containerized CI runners. While WireMock provides comprehensive administrative REST APIs (`/__admin/mappings`, `/__admin/requests`, `/__admin/scenarios`), developers in terminals frequently face friction:
- **Terminal Window Context Switching**: Switching back and forth between terminal test runs and a browser window slows down inner-loop development.
- **Complex Ad-Hoc `curl` + `jq` Pipes**: Inspecting stubs or tailing traffic requires memorizing administrative JSON structures and writing ad-hoc jq scripts.
- **Dependency Bloat**: Many third-party CLI tools require Node.js/npm, Python, or Go toolchains, which violate minimal-dependency and air-gapped development rules.
- **Headless & Container Constraints**: Remote staging servers, Docker containers, and CI pipelines lack browser environments for visual inspection.

---

## 2. How It Addresses the Problem

`wiremock-cli` provides an instant terminal observability tool that runs **directly via JDK 21 JEP 330 single-file source-code execution**:
- **Zero-Compile Inner Loop**: Run scripts immediately with zero pre-compilation step (`java wiremock-cli/WireMockCli.java` or `wm`).
- **Zero External Runtime Dependencies**: No `curl`, no `jq`, and no `npm` required. Uses standard JDK 21 libraries (`java.net.http.HttpClient`, `java.time`, and a lightweight built-in JSON tokenizer/AST).
- **Graceful Capability Degradation**: Works out of the box against any standard vanilla WireMock instance in the world, while lighting up dedicated extensions (`tcp-netem`, `stateful`, `otel`, `ui`) when detected.
- **UNIX Composability**: Preserves terminal scrollback, supports ANSI color suppression (`NO_COLOR`), and outputs clean tabular formats or raw JSON for piping.

---

## 3. Quick Start & Launchers

`wiremock-cli` ships with 1-line native launchers in `wiremock-cli/bin/`:

### Linux / macOS / Git Bash
```bash
./wiremock-cli/bin/wm --help
```

### Windows (Cmd / PowerShell)
```cmd
wiremock-cli\bin\wm.cmd --help
```

### Direct JDK 21 JEP 330 Execution
```bash
java wiremock-cli/WireMockCli.java stubs
java wiremock-cli/WireMockConsoleProxy.java
```

---

## 4. Commands & Features

### A. Server Info & Capability Matrix (`wm info`)
Probes the target WireMock instance to inspect connectivity, core endpoints, and detected extension capabilities:

```bash
wm info -s http://localhost:8080
```

```
WireMock Instance Probe: http://localhost:8080

Core Capabilities:
  ✓ Stubs Catalog        /__admin/mappings
  ✓ Request Journal      /__admin/requests
  ✓ FSM Scenarios        /__admin/scenarios

Extensions Suite Matrix:
  ✓ tcp-netem            /__admin/tcp-netem/proxies
  ✓ stateful             /__admin/stateful/models
  ✓ otel metrics         /__admin/metrics/prometheus
  ✓ console ui           /__admin/ui/
```

### B. Stub Catalog & Expression Filtering (`wm stubs`)
Lists registered stubs formatted in a colorized, aligned table or raw JSON. Supports expressive boolean expressions (`and`, `or`, `not`, key-value pairs, and regex paths):

```bash
# Filter by HTTP method and path substring
wm stubs "GET and /cases"

# Multiple HTTP methods (OR expression)
wm stubs "POST or DELETE"

# Filter by lifecycle status (active vs disabled)
wm stubs "status=disabled"

# Match via regex path
wm stubs "path~/api/v[0-9]+/cases"

# Export filtered stubs as raw JSON for piping
wm stubs "POST" --json
```

Output:
```
STATUS     METHOD  RESP   URL / PATTERN                            NAME / ID
─────────────────────────────────────────────────────────────────────────────────────
[ACTIVE]   GET     200    /api/v1/cases                            List Cases
[ACTIVE]   POST    201    /api/v1/cases                            Create Case
[DISABLED] GET     200    /api/v1/health                           Health Check

Showing 3 of 3 stubs.
```

### C. Live Request Journal Tailing (`wm tail`)
Streams or displays recent request journal events with HTTP method badges, response status codes, and roundtrip duration:

```bash
# Snapshot: view last 10 requests
wm tail

# Live Follow Mode: continuously stream incoming traffic
wm tail -f

# Filter to unmatched requests (404s) with candidate near-miss diffs
wm tail -u -f --diff

# Limit number of entries and specify poll interval
wm tail -f -n 25 --interval 500
```

Output:
```
Streaming traffic from: http://localhost:8080 (Ctrl+C to stop)
Filtering: Unmatched (404) requests only

[10:14:02] GET     200      12ms  /api/v1/cases
[10:14:05] POST    201      45ms  /api/v1/cases
[10:14:08] GET     404       2ms  /api/v1/casses-typo            ⚠️ UNMATCHED
       └─ Nearest stub match candidate / diff available in Web UI
```

### D. Network Toxic Inspection (`wm toxics`)
Inspects active L4 network impairments (latency, bandwidth limits, packet slicing) managed by `wiremock-extension-tcp-netem`:

```bash
wm toxics
```

```
Proxy: wiremock-tcp-ingress [Listen: 8474 -> Target: 8080]
  TYPE         NAME             STREAM       ATTRIBUTES
  latency      mobile-lag       DOWNSTREAM   {"latency": 300, "jitter": 50}
```

*Graceful Degradation*: When running against a vanilla WireMock server without `tcp-netem`, `wm toxics` exits cleanly with code `0` and a helpful notice:
```
Notice: wiremock-extension-tcp-netem is not installed on http://localhost:8080 (/__admin/tcp-netem/proxies returned 404).
```

### E. Standalone Web Console Proxy (`wm proxy`)
Launches the headless reverse proxy and embedded Web UI server (`WireMockConsoleProxy`):

```bash
wm proxy http://localhost:8080 19840
```
- Serves the official Vanilla ES6 Single-Page Web UI at `http://localhost:19840/__admin/ui/`.
- Transparently proxies administrative calls and application traffic to the target WireMock instance.

---

## 5. Configuration & Server Target Setup

`wiremock-cli` requires a reachable WireMock instance. By default, it attempts to connect to `http://localhost:8080`.
If your WireMock server runs on a custom port, in a Docker container, or on a remote staging host, you **must point the CLI to the server** using either of the following mechanisms:

### A. Command-Line Argument (`-s` / `--server`)
Pass `-s` or `--server` followed by the base URL to any command:
```bash
wm info -s http://staging-wiremock.internal:8080
wm stubs "GET and /cases" --server https://mock.corp.example.com
```

### B. Environment Variable (`WIREMOCK_URL`)
Define `WIREMOCK_URL` in your terminal shell, profile (`~/.bashrc` / `~/.zshrc`), or CI/CD environment to avoid specifying `-s` repeatedly:
```bash
# Linux / macOS / Git Bash
export WIREMOCK_URL="http://localhost:8080"
wm info

# Windows PowerShell
$env:WIREMOCK_URL = "http://localhost:8080"
wm info
```

### Configuration Reference

| Variable / Flag | Default | Description |
| :--- | :--- | :--- |
| `-s, --server <url>` | `http://localhost:8080` | Target WireMock base URL (overrides `WIREMOCK_URL`). |
| `WIREMOCK_URL` | `http://localhost:8080` | Environment variable fallback for target WireMock URL. |
| `--no-color` | `false` | Explicitly disables ANSI terminal color formatting. |
| `NO_COLOR` | Unset | Standard environment variable for suppressing terminal colors. |

> [!TIP]
> If the target server is down or unreachable, `wiremock-cli` exits with code `1` and prints an actionable diagnostic pointing to the target URL and configuration flags, rather than an opaque error.
---

## 6. Project Layout

```
wiremock-cli/
├── README.md                          # This documentation guide
├── WireMockCli.java                   # JEP 330 standalone source launcher
├── WireMockConsoleProxy.java          # JEP 330 standalone proxy launcher
├── bin/
│   ├── wm                             # 1-line Bash launcher (Linux/macOS/Git Bash)
│   └── wm.cmd                         # 1-line Windows Cmd / PowerShell launcher
├── build.gradle                       # Gradle test dependencies & check wiring
└── src/
    ├── main/java/com/github/mattiasgreen/wiremock/cli/
    │   └── WireMockCli.java           # Compiled CLI entrypoint & library class
    └── test/java/com/github/mattiasgreen/wiremock/cli/
        ├── WireMockCliTest.java       # Unit tests (MiniJson, QueryEvaluator, Formatter)
        └── WireMockCliIntegrationTest.java # Integration tests against ephemeral WireMock
```
