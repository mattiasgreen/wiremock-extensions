# 7. Schema-less Fielded (`CFIELD/`) Buffer Mediation and Binary Safety

Date: 2026-11-03

## Status

Accepted

## Context

Casual (XATMI / Tuxedo) applications heavily employ Fielded Buffers (`.FIELDED` / `CFIELD/`), where messages are composed of typed, indexed field entries rather than self-describing JSON or XML. Previously, `wiremock-extension-casual` relied strictly on an external field table definition file (`casual-fields.json` or `CASUAL_FIELD_TABLE`) to decode binary fielded buffers into human-readable JSON properties:

1. **Lookup Failures in Distributed Environments**: When WireMock was deployed into test or containerized environments without a mounted `casual-fields.json`, or when upstream domains introduced new unmapped fields, `CasualFieldedLookup` threw a lookup exception.
2. **Blind Base64 Fallback**: Lookup failures caused the entire request to fall back to opaque Base64 strings (`AAAAAA...`), preventing developers from viewing, matching, or asserting on specific payload fields in stubs.
3. **Response Body Control Character Corruption**: In `CasualHttpMediation.buildProxiedServeEvent()`, non-JSON responses were erroneously assigned to `Response.body()` as raw bytes. When serialized by WireMock's Request Journal and JSON logging listeners via `response.getBodyAsString()`, the raw binary control codes (including null bytes `\u0000`, `\u0001`, `\u0004`, `\u0005`) scrambled the embedded Web Console DOM tree, broke JSON log parsers, and corrupted offline stub generation.

## Decision

We introduce **Schema-less Fielded Buffer Mediation** and **Binary Response Protection** across the Casual extension:

1. **Deterministic Type Derivation from Real Field IDs**:
   According to the Casual / Tuxedo wire specification, the 64-bit real field ID encodes its concrete data type in the upper bits:
   $$\text{typeValue} = \lfloor \text{realFieldId} / \text{0x2000000} \rfloor$$
   Where `1` = `SHORT`, `2` = `LONG`, `3` = `CHAR`, `4` = `FLOAT`, `5` = `DOUBLE`, `6` = `STRING`, `7` = `BINARY`.
   WireMock now decodes all fields directly from their binary wire layout without requiring an external field table.

2. **Hybrid Field Resolution (`FLD_<realId>`)**:
   - For fields present in `CasualFieldedLookup`, the registered human-readable name is used (e.g. `FLD_USER_ID`, `FLD_CLIENT_NAME`).
   - For fields missing from the table (or when no table is configured), the gateway synthesizes a deterministic key `FLD_<realId>` (e.g. `FLD_201328009`).
   - Partial tables resolve cleanly: known fields are named, unknown fields are keyed by `FLD_<realId>`, and the entire buffer renders as a clean, structured JSON document.

3. **Bidirectional Schema-less Re-encoding in Offline Stubs**:
   `CasualBufferUtils.wrapPayload("CFIELD/", json)` parses any property matching `^FLD_(\d+)$`, derives its wire `FieldType` directly from the numeric ID, and writes the native 16-byte field header (`realId` + `length`) and value bytes into the binary buffer. Developers can mock and stub unmapped fielded services with zero field table configuration.

4. **Embedded JSON Unnesting in Binary Fields**:
   When decoding `CASUAL_FIELD_BINARY` fields, WireMock inspects the byte payload. If the bytes represent a valid UTF-8 JSON object or array, it is parsed and nested directly as structured JSON. If plain text, it is extracted as a string; otherwise, it encodes as Base64.

5. **Binary Response Stream Protection**:
   In `CasualHttpMediation.buildProxiedServeEvent()`, if a response cannot be decoded to JSON or plain text (e.g. raw octet-streams or unparseable buffers), the response body is encoded as clean Base64 ASCII bytes rather than dumping raw control bytes into WireMock's string representation. This prevents Request Journal and Web UI corruption.

## Consequences

- **Universal Observability**: Fielded buffers always render as structured, inspectable JSON in the Request Journal, Web UI, and JSON logs, even with zero field table files configured.
- **Full Offline Stubbing**: Developers can record and stub fielded services offline without depending on internal field table files.
- **Zero DOM Scrambling**: Non-printable binary responses are guaranteed to be clean Base64, keeping the UI, search filters, and log streams stable and crash-free.
- **Backward Compatibility**: Fully compatible with existing `casual-fields.json` configurations; named fields take precedence whenever available.
