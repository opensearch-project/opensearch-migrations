package org.opensearch.migrations.metadata;

import java.util.List;

import org.opensearch.migrations.metadata.CreationResult.CreationFailureType;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class GlobalMetadataCreatorResultsTest {
    @Test
    void testFatalIssueCount_hasFatalIssues() {
        var result = GlobalMetadataCreatorResults.builder()
            .componentTemplates(List.of())
            .legacyTemplates(List.of())
            .indexTemplates(List.of(
                CreationResult.builder().name("foobar").failureType(CreationFailureType.TARGET_CLUSTER_FAILURE).build(),
                CreationResult.builder().name("barfoo").failureType(CreationFailureType.TARGET_CLUSTER_FAILURE).build()
            ))
            .build();
        
        assertThat(result.fatalIssueCount(), equalTo(2L));
    }

    @Test
    void testFatalIssueCount_noFatalIssues() {
        var result = GlobalMetadataCreatorResults.builder()
            .componentTemplates(List.of())
            .legacyTemplates(List.of())
            .indexTemplates(List.of(
                CreationResult.builder().name("foobar").build(),
                CreationResult.builder().name("barfoo").failureType(CreationFailureType.ALREADY_EXISTS).build()
            ))
            .build();
        
        assertThat(result.fatalIssueCount(), equalTo(0L));
    }
}
