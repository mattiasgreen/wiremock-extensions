/**
 * Experimental in-memory stateful API simulation using closed-world AST evaluation
 * and multi-tenant correlation-isolated state stores.
 *
 * <p><b>Status: Alpha Incubation Prototype.</b>
 * For standard finite state machines, prefer native WireMock Scenarios with ID scoping.
 * For complex relational models with cascading invariants, consider provider-supplied Testcontainers.
 */
@Experimental("Alpha incubation prototype")
package com.github.mattiasgreen.wiremock.stateful;
