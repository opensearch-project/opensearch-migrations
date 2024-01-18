package org.opensearch.migrations.tracing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.opensearch.migrations.tracing.TestContext;

public class InstrumentationTest {

    protected TestContext rootContext;

    protected TestContext makeContext() { return TestContext.noOtelTracking(); }

    @BeforeEach
    protected void initializeContext() {
        rootContext = makeContext();
    }

    @AfterEach
    protected void teardownContext() {
        rootContext.close();
        rootContext = null;
    }
}
