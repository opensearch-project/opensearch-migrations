package org.opensearch.migrations.replay;

import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.datatypes.PojoUniqueSourceRequestKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import java.util.Map;
import java.util.Optional;

class ParsedHttpMessagesAsDictsTest extends InstrumentationTest {

    ParsedHttpMessagesAsDicts makeTestData() {
        return makeTestData(null, null);
    }

    @Override
    protected TestContext makeContext() {
        return TestContext.withTracking(false, true);
    }

    ParsedHttpMessagesAsDicts makeTestData(Map<String, Object> sourceResponse,
                                           Map<String, Object> targetResponse) {
        return new ParsedHttpMessagesAsDicts(rootContext.getTestTupleContext(),
                Optional.empty(), Optional.ofNullable(sourceResponse),
                Optional.empty(), Optional.ofNullable(targetResponse));
    }

}