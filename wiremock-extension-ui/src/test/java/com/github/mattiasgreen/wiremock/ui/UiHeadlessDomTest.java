package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

public class UiHeadlessDomTest {

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(
                WireMockConfiguration.options()
                        .dynamicPort()
                        .extensions(new UiAdminApiEndpoint())
        );
        server.start();

        server.stubFor(get(urlEqualTo("/api/v1/items"))
                .withName("List Items Mock")
                .willReturn(okJson("[{\"id\":101,\"name\":\"Item 1\"}]")));
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    void testHeadlessBrowserLoadsUiAndRendersDom() throws Exception {
        try (WebClient webClient = new WebClient(BrowserVersion.CHROME)) {
            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setThrowExceptionOnScriptError(false);
            webClient.getOptions().setCssEnabled(false);

            HtmlPage page = webClient.getPage(server.baseUrl() + "/__admin/ui");
            assertThat(page.getTitleText()).isEqualTo("WireMock Stub Viewer");

            webClient.waitForBackgroundJavaScript(2000);

            HtmlElement searchBox = page.getHtmlElementById("search-box");
            assertThat(searchBox).isNotNull();

            HtmlElement stubList = page.getHtmlElementById("stub-list");
            assertThat(stubList).isNotNull();
            assertThat(stubList.asNormalizedText()).isNotEmpty();

            HtmlElement statStubs = page.getHtmlElementById("stat-stubs");
            assertThat(statStubs).isNotNull();

            HtmlElement tabTester = page.getHtmlElementById("tab-tester");
            assertThat(tabTester).isNotNull();

            HtmlElement journalSearch = page.getHtmlElementById("journal-search");
            assertThat(journalSearch).isNotNull();

            HtmlElement testerUrl = page.getHtmlElementById("tester-url");
            assertThat(testerUrl).isNotNull();

            HtmlElement btnTesterSend = page.getHtmlElementById("btn-tester-send");
            assertThat(btnTesterSend).isNotNull();
        }
    }

    @Test
    void testDeepLinkingToTesterTab() throws Exception {
        try (WebClient webClient = new WebClient(BrowserVersion.CHROME)) {
            webClient.getOptions().setJavaScriptEnabled(true);
            webClient.getOptions().setThrowExceptionOnScriptError(true);
            webClient.getOptions().setCssEnabled(false);

            HtmlPage page = webClient.getPage(server.baseUrl() + "/__admin/ui");
            webClient.waitForBackgroundJavaScript(2000);

            // Click the HTTP Tester tab
            HtmlElement tabBtn = page.getFirstByXPath("//button[@data-tab='tab-tester']");
            assertThat(tabBtn).isNotNull();
            tabBtn.click();
            webClient.waitForBackgroundJavaScript(1000);

            HtmlElement tabTester = page.getHtmlElementById("tab-tester");
            assertThat(tabTester).isNotNull();
            assertThat(tabTester.getAttribute("class")).contains("active");

            org.htmlunit.html.HtmlTextInput testerUrl = page.getHtmlElementById("tester-url");
            assertThat(testerUrl.getValue()).isEqualTo("/api/v1/users");

            org.htmlunit.html.HtmlSelect testerMethod = page.getHtmlElementById("tester-method");
            assertThat(testerMethod.getSelectedOptions().get(0).getValueAttribute()).isEqualTo("GET");
        }
    }
}
