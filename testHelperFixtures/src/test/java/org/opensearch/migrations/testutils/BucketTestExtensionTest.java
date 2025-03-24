package org.opensearch.migrations.testutils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class BucketTestExtensionTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19})
    @ExtendWith(Test.class)
    public void testBucketTestExtensionEvaluation() {
    }

    public static class Test implements BeforeTestExecutionCallback {
        @Override
        public void beforeTestExecution(ExtensionContext context) {
            Integer stripingTotal = Integer.getInteger("test.striping.total");
            Integer stripingIndex = Integer.getInteger("test.striping.index");

            Assumptions.assumeTrue(stripingTotal != null && stripingIndex != null,
                () -> "testBucketTestExtensionEvaluation requires set test.striping.total and test.striping.index");

            int uniqueTestIdHash = context.getUniqueId().hashCode();
            var testIndex = Math.toIntExact(Long.remainderUnsigned(uniqueTestIdHash, stripingTotal));

            Assertions.assertEquals(testIndex, stripingIndex,
                "testIndex must match configured striping.index");
        }
    }
}
