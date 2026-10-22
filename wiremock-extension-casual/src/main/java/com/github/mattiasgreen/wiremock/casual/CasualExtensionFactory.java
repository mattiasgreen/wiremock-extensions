package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

/**
 * Extension factory enabling auto-discovery of Casual admin endpoints in WireMock standalone.
 */
public class CasualExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices services) {
        return List.of(new CasualAdminEndpoint());
    }
}
