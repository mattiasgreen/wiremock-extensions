package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Headless in-browser unit tests verifying ES modules without external JS bundlers or Node.js.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiModuleUnitTest {

    private WireMockServer wireMockServer;
    private Playwright playwright;
    private Browser browser;
    private Page page;

    @BeforeAll
    void startAll() {
        wireMockServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new UiAdminApiEndpoint()));
        wireMockServer.start();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        page = browser.newPage();

        // Navigate to the UI to ensure same-origin context for ES module imports
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
    }

    @AfterAll
    void stopAll() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    private Object evalJs(String expression) {
        return page.evaluate(expression);
    }

    @Test
    @DisplayName("evaluateQuery: Basic free-text and boolean AND / OR expression parsing")
    void testEvaluateQueryExpressions() {
        String script =
                """
            async () => {
                const { evaluateQuery } = await import('./modules/filter.js');

                const corpus = "customer 1234 product abc5 region emea";
                const matcher = token => corpus.includes(token.toLowerCase());

                return {
                    emptyMatches: evaluateQuery("", matcher),
                    singleTermMatches: evaluateQuery("1234", matcher),
                    singleTermMisses: evaluateQuery("9999", matcher),
                    andExplicitMatches: evaluateQuery("1234 AND abc5", matcher),
                    andExplicitMisses: evaluateQuery("1234 AND 9999", matcher),
                    andImplicitWhitespaceMatches: evaluateQuery("1234 abc5", matcher),
                    andImplicitWhitespaceMisses: evaluateQuery("1234 9999", matcher),
                    orMatchesFirst: evaluateQuery("1234 OR 9999", matcher),
                    orMatchesSecond: evaluateQuery("9999 OR abc5", matcher),
                    orMissesBoth: evaluateQuery("8888 OR 9999", matcher),
                    andBindsTighterThanOr: evaluateQuery("1234 AND 9999 OR emea", matcher),
                    caseInsensitiveOperators: evaluateQuery("1234 and abc5 or 9999", matcher)
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("emptyMatches")).isTrue();
        assertThat(result.get("singleTermMatches")).isTrue();
        assertThat(result.get("singleTermMisses")).isFalse();
        assertThat(result.get("andExplicitMatches")).isTrue();
        assertThat(result.get("andExplicitMisses")).isFalse();
        assertThat(result.get("andImplicitWhitespaceMatches")).isTrue();
        assertThat(result.get("andImplicitWhitespaceMisses")).isFalse();
        assertThat(result.get("orMatchesFirst")).isTrue();
        assertThat(result.get("orMatchesSecond")).isTrue();
        assertThat(result.get("orMissesBoth")).isFalse();
        assertThat(result.get("andBindsTighterThanOr")).isTrue();
        assertThat(result.get("caseInsensitiveOperators")).isTrue();
    }

    @Test
    @DisplayName("matchesStub: Basic URL, method, name, and tag baseline matching")
    void testMatchesStubBaseline() {
        String script =
                """
            async () => {
                const { matchesStub } = await import('./modules/filter.js');

                const stub = {
                    id: "stub-1",
                    name: "Get User Orders",
                    request: {
                        method: "GET",
                        url: "/api/v1/orders"
                    },
                    metadata: {
                        tags: ["ecommerce", "checkout"]
                    }
                };

                return {
                    matchByMethod: matchesStub(stub, "get", "/api/v1/orders"),
                    matchByUrl: matchesStub(stub, "orders", "/api/v1/orders"),
                    matchByName: matchesStub(stub, "User Orders", "/api/v1/orders"),
                    matchByTag: matchesStub(stub, "checkout", "/api/v1/orders"),
                    noMatch: matchesStub(stub, "payments", "/api/v1/orders"),
                    matchAnd: matchesStub(stub, "orders AND checkout", "/api/v1/orders"),
                    matchOr: matchesStub(stub, "payments OR orders", "/api/v1/orders")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("matchByMethod")).isTrue();
        assertThat(result.get("matchByUrl")).isTrue();
        assertThat(result.get("matchByName")).isTrue();
        assertThat(result.get("matchByTag")).isTrue();
        assertThat(result.get("noMatch")).isFalse();
        assertThat(result.get("matchAnd")).isTrue();
        assertThat(result.get("matchOr")).isTrue();
    }

    @Test
    @DisplayName("matchesJournalEntry: Method, URL, status, and stateful entity baseline matching")
    void testMatchesJournalEntryBaseline() {
        String script =
                """
            async () => {
                const { matchesJournalEntry } = await import('./modules/filter.js');

                const req = {
                    id: "req-1",
                    request: {
                        method: "POST",
                        url: "/api/v1/checkout"
                    },
                    response: {
                        status: 201
                    },
                    stubMapping: {
                        name: "Create Order"
                    }
                };

                return {
                    matchByMethod: matchesJournalEntry(req, "POST"),
                    matchByUrl: matchesJournalEntry(req, "checkout"),
                    matchByStatus: matchesJournalEntry(req, "201"),
                    matchByStubName: matchesJournalEntry(req, "Order"),
                    noMatch: matchesJournalEntry(req, "customers"),
                    matchAnd: matchesJournalEntry(req, "POST AND 201"),
                    matchOr: matchesJournalEntry(req, "500 OR 201")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("matchByMethod")).isTrue();
        assertThat(result.get("matchByUrl")).isTrue();
        assertThat(result.get("matchByStatus")).isTrue();
        assertThat(result.get("matchByStubName")).isTrue();
        assertThat(result.get("noMatch")).isFalse();
        assertThat(result.get("matchAnd")).isTrue();
        assertThat(result.get("matchOr")).isTrue();
    }
}
