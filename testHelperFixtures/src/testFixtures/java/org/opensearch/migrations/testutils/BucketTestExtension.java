package org.opensearch.migrations.testutils;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit Jupiter extension that enables test striping (sharding) to distribute test execution
 * across multiple runs. This is useful for parallelizing test execution in CI/CD pipelines.
 * 
 * Usage: ./gradlew test -Dtest.striping.total=N -Dtest.striping.index=I
 * where N is the total number of buckets (e.g., 3)
 * and I is the current bucket index (0 to N-1)
 */
@Slf4j
public class BucketTestExtension implements BeforeTestExecutionCallback {
    // Use a unique namespace for our extension to avoid conflicts with other extensions
    private static final ExtensionContext.Namespace BUCKET_NAMESPACE = 
        ExtensionContext.Namespace.create(BucketTestExtension.class);

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        var stripingTotal = Integer.getInteger("test.striping.total", 1);
        var stripingIndex = Integer.getInteger("test.striping.index", 0);

        if (stripingTotal <= 0) {
            log.error("Invalid striping total: {}. Must be a positive integer.", stripingTotal);
            return; // Don't skip any tests if the configuration is invalid
        }
        
        if (stripingTotal > 1) {
            log.info("Test striping enabled: total={}, index={}", stripingTotal, stripingIndex);
        }

        var uniqueTestIdHash = context.getUniqueId().hashCode();
        var testIndex = Math.toIntExact(Long.remainderUnsigned(uniqueTestIdHash, stripingTotal));
        
        // Skip tests that are not in the selected bucket
        Assumptions.assumeTrue(stripingIndex.equals(testIndex),
            () -> "Skipping test " + context.getDisplayName() + " due to striping index " + (testIndex));
    }
}
