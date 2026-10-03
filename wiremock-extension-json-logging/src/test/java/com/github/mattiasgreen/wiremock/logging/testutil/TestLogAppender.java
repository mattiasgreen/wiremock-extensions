package com.github.mattiasgreen.wiremock.logging.testutil;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TestLogAppender extends AppenderBase<ILoggingEvent> {

    private static final List<ILoggingEvent> EVENTS = Collections.synchronizedList(new ArrayList<>());

    @Override
    protected void append(ILoggingEvent eventObject) {
        EVENTS.add(eventObject);
    }

    public static List<ILoggingEvent> getEvents() {
        return new ArrayList<>(EVENTS);
    }

    public static void clear() {
        EVENTS.clear();
    }
}
