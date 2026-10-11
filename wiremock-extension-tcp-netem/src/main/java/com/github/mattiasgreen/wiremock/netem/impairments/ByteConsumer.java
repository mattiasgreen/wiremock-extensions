package com.github.mattiasgreen.wiremock.netem.impairments;

import java.io.IOException;

@FunctionalInterface
public interface ByteConsumer {
    void accept(byte[] buffer, int offset, int length) throws IOException;
}
