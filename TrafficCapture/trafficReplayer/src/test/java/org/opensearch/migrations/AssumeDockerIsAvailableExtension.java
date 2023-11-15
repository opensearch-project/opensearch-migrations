package org.opensearch.migrations;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

import java.util.concurrent.Callable;

public class AssumeDockerIsAvailableExtension implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            final var msg = "Skipping test because Docker is not available";
            context.publishReportEntry(msg);
            Assumptions.abort(msg);
        }
    }

    public static <T> T assumeNoThrow(Callable<T> c) {
        try {
            return c.call();
        } catch (Exception e) {
            Assumptions.abort("Aborting due to caught exception: "+e);
            return null;
        }
    }
}