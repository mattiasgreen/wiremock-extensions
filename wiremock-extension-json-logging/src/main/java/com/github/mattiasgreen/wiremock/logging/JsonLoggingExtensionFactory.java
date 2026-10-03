package com.github.mattiasgreen.wiremock.logging;

import com.github.mattiasgreen.wiremock.logging.mdc.MdcRequestFilter;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

public class JsonLoggingExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices services) {
        return List.of(new MdcRequestFilter(), new JsonLoggingListener());
    }
}
