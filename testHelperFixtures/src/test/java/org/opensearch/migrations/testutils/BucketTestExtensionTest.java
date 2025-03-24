package org.opensearch.migrations.testutils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(BucketTestExtensionTest.ExtensionContextParameterResolver.class)
// Auto Extended with BucketTestExtension
public class BucketTestExtensionTest {
    
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19})
    public void testBucketTestExtension(Integer value, ExtensionContext context) {
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
