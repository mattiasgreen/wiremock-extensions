# UI Design System & Cognitive Ergonomics Guidelines

> **Status**: Platform-Agnostic UI & Ergonomics Standard  
> **Audience**: UI/UX Designers, Frontend Developers, Autonomous Coding Agents  
> **Applicability**: Web SPA, Native Desktop (Swing), Terminal TUI, Mobile Apps

---

## 1. Core Cognitive Principles

### 1.1 Hick's Law & Progressive Disclosure (Minimizing Cognitive Overload)
- **Never present an unstructured "button soup"**: Do not dump 15 unorganized buttons into a single horizontal toolbar.
- **Categorize action tiers**:
  - **Primary Action** (High emphasis, 1 per view): e.g. `Send Request`, `Create Stub`.
  - **Secondary Actions** (Neutral emphasis): e.g. `Copy as cURL`, `Format JSON`, `Filter`.
  - **Destructive Actions** (Alert emphasis, distinct separation): e.g. `Delete Stub`, `Clear Journal`. Always require confirmation or provide undo.
- **Progressive Disclosure**: Show high-level summary cards/rows first (Status, Method, URL, Latency). Expand deep diagnostic details (Headers, Query Params, Raw JSON, Matcher Diffs) on selection or tab switch.

### 1.2 Fitts's Law (Spatial Proximity to Data)
- Place contextual actions directly adjacent to the data they operate on.
  - *Example*: In the Request Journal inspector, `"Send in Tester ->"` and `"Copy as cURL"` must sit directly at the top of the selected request details panel, not across the screen in the top-level window menu.
- Ensure click/tap target areas have comfortable padding (minimum 28-36px height for desktop buttons).

### 1.3 Feedback Loops & Cognitive Assurance
- **Immediate Sensory Feedback**:
  - When copying to clipboard: Never fail silently. Always display a visual confirmation toast or label flash (`"Copied to clipboard!"`) that fades after 1.5 seconds.
  - When dispatching an HTTP request: Disable the send button or show a loading indicator until the response resolves.
- **The 30-Second Rule**: A developer must be able to complete their most frequent task (e.g. Inspect Unmatched Request -> Fix Stub -> Replay in Tester) in under 30 seconds with fewer than 4 interactions.

---

## 2. Information Density & Typography

| Element Role | Style / Treatment | Rationale |
| :--- | :--- | :--- |
| **HTTP Methods (GET, POST...)** | Monospace bold badge (`POST`, `GET`, `DELETE`) | Instant visual scanning across tables and lists. |
| **URLs & Paths (`/api/v1/...`)** | Monospace, medium weight, left-aligned | Precise path reading; avoid truncation when space permits. |
| **Payloads & Headers** | Monospace syntax-highlighted (or formatted block) | Essential for reading JSON keys, values, and whitespace. |
| **Status Badges** | Rounded pill container with high-contrast text | Quick glance identification of match status and HTTP codes. |

---

## 3. Semantic Color Tokens

Color must provide immediate meaning without relying solely on hue for accessibility:

| Semantic Role | Token / Hex (Dark / Light) | Usage |
| :--- | :--- | :--- |
| **Success (2xx)** | `#10B981` (Emerald) | Matched requests, `200 OK`, `201 Created`, active stubs. |
| **Redirect (3xx)** | `#3B82F6` (Sapphire Blue) | `301`, `302`, informational states. |
| **Warning / Unmatched (4xx)** | `#F59E0B` (Amber Orange) | Unmatched requests (`404`), client validation errors (`400`, `422`). |
| **Critical Failure (5xx)** | `#EF4444` (Crimson Red) | Server faults (`500`), connection drops, syntax errors. |
| **Disabled / Inactive** | `#6B7280` (Muted Grey) | Disabled stubs, inactive scenario states. |
| **Accent / Brand** | `#6366F1` (Indigo) | Primary buttons, active navigation indicators, selection borders. |

---

## 4. The Negative Space: Empty, Loading, and Error States

Every view or list component must explicitly define its state across four lifecycle phases:

```
┌────────────────────────────────────────────────────────┐
│             View State Progression Model               │
│                                                        │
│  [Loading / Connecting] ───► [Active Data Populated]   │
│           │                               │            │
│           ▼                               ▼            │
│  [Disconnected / Error]        [Empty Results / Filter]│
└────────────────────────────────────────────────────────┘
```

1. **Initial / Empty Catalog**:
   - *Bad*: An empty grey rectangle.
   - *Good*: Friendly empty state card with guidance:
     > *"No stubs registered yet. Create a new stub or import an OpenAPI specification to get started."* with a direct action button: `[+ Create Stub]`.
2. **Filter Zero-Results**:
   - *Good*: *"No stubs match the filter 'abc'. [Clear Filter]"*.
3. **Journal Idle State**:
   - *Good*: *"Listening for incoming HTTP requests on port 8080... Make a call to see traffic recorded here in real time."*.
4. **Disconnected / Server Offline State**:
   - *Good*: Persistent reconnection banner at top with a retry button: *"Cannot connect to WireMock at http://localhost:8080. [Retry Connection]"*.

---

## 5. Keyboard Navigation & Accelerators

Power users and QA engineers rely on muscle memory:

| Action | Standard Keybinding |
| :--- | :--- |
| **Search / Filter** | `Ctrl+F` / `Cmd+F` (focuses search box) |
| **Dismiss / Clear** | `Escape` (clears active search or closes open modal) |
| **Execute Request** | `Ctrl+Enter` / `Cmd+Enter` (within HTTP Tester) |
| **Switch Tabs** | `Ctrl+1` through `Ctrl+4` |
| **Delete Selected** | `Delete` / `Backspace` (prompts confirmation) |
