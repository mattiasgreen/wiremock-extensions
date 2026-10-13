# WireMock Casual (XATMI) Protocol Extension

In-process Casual domain gateway and XATMI protocol simulation extension for WireMock Standalone.

## Overview

Enables WireMock to act as an in-process Casual Domain Gateway listening on a TCP port. Clients communicating via Casual wire protocol (`tpcall`, `tpacall`) have their requests intercepted and matched against WireMock stubs, allowing realistic simulation of legacy and distributed transaction architectures without requiring a full C++ Casual domain or Jakarta EE application server.

## Features

- **Direct Wire Protocol Decoding**: Pure Java SE Netty-based framing decoder utilizing `se.laz.casual:casual-network-protocol`.
- **Domain Handshake Support**: Responds to Casual domain connect and discovery handshakes.
- **Service Request Mapping**: Translates Casual service calls (`tpcall`) to internal WireMock HTTP requests (`POST /__casual/services/{serviceName}`).
- **Buffer Support**: Supports `JSON` and `FIELDED` buffer types.
- **WireMock Stub Engine Integration**: Leverages WireMock's full stub matching, request templating, stateful scenarios, and request journal.
