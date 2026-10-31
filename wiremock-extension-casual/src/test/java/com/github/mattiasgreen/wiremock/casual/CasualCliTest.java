package com.github.mattiasgreen.wiremock.casual;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CasualCliTest {

    @Test
    @DisplayName("Should display help message on --help or -h")
    void shouldDisplayHelp() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = CasualCli.run(new String[] {"--help"}, System.in, out, err);

        assertThat(exitCode).isEqualTo(0);
        String text = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("CasualClient - Standalone client");
        assertThat(text).contains("Commands:");
        assertThat(text).contains("Options:");
    }

    @Test
    @DisplayName("Should exit with error on unknown command")
    void shouldErrorOnUnknownCommand() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = CasualCli.run(new String[] {"unknownCmd"}, System.in, out, err);

        assertThat(exitCode).isEqualTo(1);
        String errText = errBytes.toString(StandardCharsets.UTF_8);
        assertThat(errText).contains("Unknown command 'unknowncmd'");
    }
}
