package org.opensearch.migrations.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class IInstrumentationAttributesTest {

    private static final AttributeKey<String> OVERRIDE_KEY = AttributeKey.stringKey("overrideKey");
    private static final AttributeKey<String> UNIQUE_KEY = AttributeKey.stringKey("uniqueKey");

    private static class AContext extends BaseNestedSpanContext<RootOtelContext, RootOtelContext>{
        protected AContext(RootOtelContext rootScope, RootOtelContext enclosingScope) {
            super(rootScope, enclosingScope);
        }

        @Override
        public String getActivityName() {
            return "A";
        }

        @Override
        public CommonScopedMetricInstruments getMetrics() {
            return null;
        }

        @Override
        public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return super.fillAttributesForSpansBelow(builder)
                    .put(OVERRIDE_KEY, "a-toBeOverridden")
                    .put(UNIQUE_KEY, "a-toStay");
        }
    }

    private static class BContext extends BaseNestedSpanContext<RootOtelContext, AContext>{
        protected BContext(RootOtelContext rootScope, AContext enclosingScope) {
            super(rootScope, enclosingScope);
        }

        @Override
        public String getActivityName() {
            return "B";
        }

        @Override
        public CommonScopedMetricInstruments getMetrics() {
            return null;
        }

        @Override
        public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return super.fillAttributesForSpansBelow(builder)
                    .put(OVERRIDE_KEY, "b");
        }
    }

    @Test
    public void getPopulatedAttributesAreOverrideCorrectly() {
        var rootCtx = new RootOtelContext("test");
        var aCtx = new AContext(rootCtx, rootCtx);
        var bCtx = new BContext(rootCtx, aCtx);

        Optional.ofNullable(aCtx.getPopulatedSpanAttributes()).ifPresent(attrs-> {
                    Assertions.assertEquals("a-toBeOverridden", attrs.get(OVERRIDE_KEY));
                    Assertions.assertEquals("a-toStay", attrs.get(UNIQUE_KEY));
                });
        Optional.ofNullable(bCtx.getPopulatedSpanAttributes()).ifPresent(attrs-> {
            Assertions.assertEquals("b", attrs.get(OVERRIDE_KEY));
            Assertions.assertEquals("a-toStay", attrs.get(UNIQUE_KEY));
        });
    }
}