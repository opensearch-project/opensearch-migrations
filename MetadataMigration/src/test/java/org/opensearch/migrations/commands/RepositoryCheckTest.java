package org.opensearch.migrations.commands;

import java.nio.file.Files;

import org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

class RepositoryCheckTest {
    @TempDir
    java.nio.file.Path tempDir;

    @Test
    void emptyRepositoryIsPartiallyVerifiedAndSuccessful() {
        var arguments = new RepositoryCheckArgs();
        arguments.repoUri = tempDir.toUri().toString();

        var result = new RepositoryCheck(arguments).execute();

        assertThat(result.getExitCode(), equalTo(0));
        assertThat(result.check().status(),
            equalTo(RepositoryAccessCheckResult.Status.PARTIALLY_VERIFIED));
        assertThat(result.asCliOutput(), containsString("Partially verified"));
        assertThat(result.asJsonOutput().get("status").asText(), equalTo("partially_verified"));
    }

    @Test
    void readableRepositoryIsValid() throws Exception {
        Files.writeString(tempDir.resolve("index-0"), "{}");
        var arguments = new RepositoryCheckArgs();
        arguments.repoUri = tempDir.toUri().toString();

        var result = new RepositoryCheck(arguments).execute();

        assertThat(result.getExitCode(), equalTo(0));
        assertThat(result.check().status(), equalTo(RepositoryAccessCheckResult.Status.VALID));
        assertThat(result.asJsonOutput().get("stages").get(1).get("status").asText(), equalTo("passed"));
    }

    @Test
    void missingRepositoryFails() {
        var arguments = new RepositoryCheckArgs();
        arguments.repoUri = tempDir.resolve("missing").toUri().toString();

        var result = new RepositoryCheck(arguments).execute();

        assertThat(result.getExitCode(), equalTo(1));
        assertThat(result.check().status(), equalTo(RepositoryAccessCheckResult.Status.FAILED));
        assertThat(result.getErrorMessage(), containsString("Unable to list"));
    }
}
