package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsLogBuilder;

import java.util.StringJoiner;

public class MockMetricsBuilder extends MetricsLogBuilder {
    StringJoiner attributeLogger = new StringJoiner("|");

    public MockMetricsBuilder() {
        super(null);
    }

    @Override
    public MetricsLogBuilder setAttribute(MetricsAttributeKey key, Object value) {
        attributeLogger.add(key + ":" + value);
        return this;
    }

    public String getLoggedAttributes() {
        return attributeLogger.toString();
    }
}
