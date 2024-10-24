package org.opensearch.migrations.commands;

import org.opensearch.migrations.MetadataMigration;
import org.opensearch.migrations.Version;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

class EvaluateTest {

    @Test
    void evaluate_failsUnexpectedException() {
        var args = new EvaluateArgs();
        args.sourceVersion = Version.fromString("ES 7.10");
        args.fileSystemRepoPath = "";

        var meta = new MetadataMigration();
        var context = mock(RootMetadataMigrationContext.class);
 
        var results = meta.evaluate(args).execute(context);

        assertThat(results.getExitCode(), equalTo(Evaluate.UNEXPECTED_FAILURE_CODE));
    }
}
