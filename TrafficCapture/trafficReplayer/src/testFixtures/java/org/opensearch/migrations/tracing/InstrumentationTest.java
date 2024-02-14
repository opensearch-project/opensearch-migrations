package org.opensearch.migrations.tracing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

public class InstrumentationTest {

    protected TestContext rootContext;

    protected TestContext makeInstrumentationContext() {
        return TestContext.noOtelTracking();
    }

    @BeforeEach
    protected void initializeContext() {
        rootContext = makeInstrumentationContext();
    }

    @AfterEach
    protected void teardownContext() {
        rootContext.close();
        rootContext = null;
    }
}
