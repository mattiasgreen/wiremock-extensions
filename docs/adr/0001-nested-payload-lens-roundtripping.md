# Stacked Nested Payload Lens for Round-Trip Escaped Payload Editing

Enterprise HTTP and Casual XATMI traffic frequently nests escaped payloads inside outer envelopes (e.g., XML inside an escaped JSON string inside a JSON response, or JSON inside an XML `CDATA` or Base64 field). Rather than unescaping nested structures in place inside a single mixed-grammar `<textarea>`—which destroys syntax boundaries for `JSON.parse` and `DOMParser` and loses whether a node was originally JSON-escaped, XML entity-escaped, `CDATA`-wrapped, or Base64-encoded—we use a **Stacked Nested Payload Lens** backed by a **Container Encoding Envelope**.

## Considered Options

- **Single Free-Form Mixed-Grammar Editor**: Unescape all nested JSON and XML strings directly inside the main editor text buffer. Rejected because mixed unquoted XML inside JSON is syntactically invalid, making reliable re-escaping and distinguishing literal `<` strings from embedded XML documents impossible.
- **Full GUI Tree/Table Property Editor**: Replace text editors with a collapsible form tree. Rejected because engineers need fast copy-paste, raw text manipulation, and intentional syntax-error injection for negative testing.
- **Stacked Nested Payload Lens with Container Encoding Envelope (Chosen)**: Keep the outer editor in valid wire grammar (with a live **Detected Embedded Payloads Bar** for textareas and **Truncated Payload Previews** for read-only viewers), and open any nested node in a focused sub-editor stack that validates and re-encodes changes back up the parent hierarchy.
