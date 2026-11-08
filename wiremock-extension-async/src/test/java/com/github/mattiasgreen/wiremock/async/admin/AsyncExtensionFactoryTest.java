package com.github.mattiasgreen.wiremock.async.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncExtensionFactoryTest {

    @Test
    @DisplayName("Should instantiate both AsyncAdminEndpoint and AsyncMessagePublishingListener")
    void shouldCreateAsyncExtensions() {
        AsyncExtensionFactory factory = new AsyncExtensionFactory();
        List<Extension> extensions = factory.create(null);

        assertThat(extensions).hasSize(2);
        assertThat(extensions.stream().anyMatch(e -> e instanceof AsyncAdminEndpoint))
                .isTrue();
        assertThat(extensions.stream().anyMatch(e -> e.getName().equals("async-message-publisher")))
                .isTrue();
    }

    @Test
    @DisplayName("Should be discoverable via java.util.ServiceLoader")
    void shouldBeDiscoverableViaSpi() {
        ServiceLoader<ExtensionFactory> loader = ServiceLoader.load(ExtensionFactory.class);
        boolean found = false;
        for (ExtensionFactory factory : loader) {
            if (factory instanceof AsyncExtensionFactory) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }
}
