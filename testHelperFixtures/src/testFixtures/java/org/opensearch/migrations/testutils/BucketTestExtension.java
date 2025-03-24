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
 * 
 * This extension implements ExecutionCondition which is evaluated before any other extension,
 * making it more compatible with other extensions like MockitoExtension.
 */
@Slf4j
public class BucketTestExtension implements BeforeTestExecutionCallback {

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        var stripingTotal = Integer.getInteger("test.striping.total", 1);
        var stripingIndex = Integer.getInteger("test.striping.index", 0);

        if (stripingIndex < 0 || stripingIndex >= stripingTotal) {
            log.atError()
                .setMessage("Invalid striping values, received stripingIndex: {}, stripingTotal: {}.\n" +
                           "Requires 0 <= stripingIndex < stripingTotal")
                .addArgument(stripingIndex)
                .addArgument(stripingTotal);
                throw new AssertionError("Invalid striping configuration, running all tests");
        }

        var uniqueTestIdHash = context.getUniqueId().hashCode();
        var testIndex = Math.toIntExact(Long.remainderUnsigned(uniqueTestIdHash, stripingTotal));

        Assumptions.assumeTrue(stripingIndex.equals(testIndex), 
            "Test excluded from bucket " + stripingIndex + " (belongs to bucket " + testIndex + ") of " + stripingTotal);
    }
}
