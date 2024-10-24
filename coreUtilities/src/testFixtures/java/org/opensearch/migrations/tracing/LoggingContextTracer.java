package org.opensearch.migrations.tracing;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingContextTracer implements IContextTracker {

    private static final String CREATED_MESSAGE = "<< Start: {}";
    private static final String CLOSED_MESSAGE = ">> Close: {} {}";

    @Override
    public void onContextCreated(final IScopedInstrumentationAttributes context) {
        log.atDebug().setMessage(CREATED_MESSAGE).addArgument(context::getActivityName).log();
    }

    @Override
    public void onContextClosed(IScopedInstrumentationAttributes context) {
        log.atDebug()
            .setMessage(CLOSED_MESSAGE)
            .addArgument(context::getActivityName)
            .addArgument(context::getPopulatedSpanAttributes)
            .log();
    }
}
