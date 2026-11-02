package wiremock;

import com.github.tomakehurst.wiremock.standalone.WireMockServerRunner;

/**
 * Standard WireMock Standalone CLI runner entrypoint.
 * Allows running official WireMock standalone CLI commands directly
 * from the fat JAR (e.g. java -cp wiremock-*-standalone.jar wiremock.Run --port 8080).
 */
public class Run extends WireMockServerRunner {

    public static void main(String... args) {
        new WireMockServerRunner().run(args);
    }
}
