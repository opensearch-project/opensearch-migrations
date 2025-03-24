package org.opensearch.migrations.testutils;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith(BucketTestExtensionTest.ExtensionContextParameterResolver.class)
public class BucketTestExtensionTest {

    static Stream<Integer> stripingIndices() {
        int total = Integer.getInteger("test.striping.total", 1);
        return IntStream.range(0, total).boxed();
    }

    @ParameterizedTest
    @MethodSource("stripingIndices")
    public void testBucketTestExtension(Integer ignored, ExtensionContext context) {
        var stripingTotal = Integer.getInteger("test.striping.total", 1);
        var stripingIndex = Integer.getInteger("test.striping.index", 0);

        int uniqueTestIdHash = context.getUniqueId().hashCode();
        int testIndex = Math.toIntExact(Long.remainderUnsigned(uniqueTestIdHash, stripingTotal));

        Assertions.assertEquals(testIndex, stripingIndex);
    }

    public static class ExtensionContextParameterResolver implements ParameterResolver {
        @Override
        public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            return parameterContext.getParameter().getType().equals(ExtensionContext.class);
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            return extensionContext;
        }
    }
}
