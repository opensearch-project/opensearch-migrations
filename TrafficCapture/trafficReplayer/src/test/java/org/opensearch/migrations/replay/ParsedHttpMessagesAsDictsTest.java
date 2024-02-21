package org.opensearch.migrations.replay;

import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import java.util.Map;
import java.util.Optional;

class ParsedHttpMessagesAsDictsTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    ParsedHttpMessagesAsDicts makeTestData(Map<String, Object> sourceResponse,
                                           Map<String, Object> targetResponse) {
        return new ParsedHttpMessagesAsDicts(rootContext.getTestTupleContext(),
                Optional.empty(), Optional.ofNullable(sourceResponse),
                Optional.empty(), Optional.ofNullable(targetResponse));
    }

}