package org.opensearch.migrations.testutils;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.function.IntConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Helper class to keep retrying ports against a Consumer until the
 * Consumer doesn't throw an exception.
 */
@Slf4j
public class PortFinder {

    private PortFinder() {}

    private static final int MAX_PORT_TRIES = 100;

    public static class ExceededMaxPortAssigmentAttemptException extends Exception {
        public ExceededMaxPortAssigmentAttemptException(Throwable cause) {
            super(cause);
        }
    }

    public static int retryWithNewPortUntilNoThrow(IntConsumer r) throws ExceededMaxPortAssigmentAttemptException {
        int numTries = 0;
        while (true) {
            try {
                int port = findOpenPort();
                r.accept(port);
                return port;
            } catch (Exception e) {
                if (++numTries >= MAX_PORT_TRIES) {
                    log.atError()
                        .setCause(e)
                        .setMessage("Exceeded max tries {} giving up")
                        .addArgument(MAX_PORT_TRIES)
                        .log();
                    throw new ExceededMaxPortAssigmentAttemptException(e);
                }
                log.atWarn().setCause(e).setMessage("Eating exception and trying again").log();
            }
        }
    }

    public static int findOpenPort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            log.info("Open port found: " + port);
            return port;
        } catch (IOException e) {
            log.error("Failed to find an open port: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
