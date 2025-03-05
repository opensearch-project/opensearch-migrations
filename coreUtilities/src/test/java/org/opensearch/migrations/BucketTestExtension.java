package org.opensearch.migrations;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BucketTestExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        var stripingTotal = Integer.getInteger("test.striping.total", 1);
        var stripingIndex = Integer.getInteger("test.striping.index", 0);

        var uniqueTestIdHash = context.getUniqueId().hashCode();
        var testIndex = Math.toIntExact(Long.remainderUnsigned(uniqueTestIdHash, stripingTotal));
        // Skip tests that are not in the selected bucket
        Assumptions.assumeTrue(stripingIndex.equals(testIndex),
            () -> "Skipping test " + context.getDisplayName() + " due to striping index " + (testIndex));
    }
}