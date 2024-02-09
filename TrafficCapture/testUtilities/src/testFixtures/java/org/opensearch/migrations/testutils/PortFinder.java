package org.opensearch.migrations.testutils;


import lombok.extern.slf4j.Slf4j;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Helper class to keep retrying ports against a Consumer until the
 * Consumer doesn't throw an exception.
 */
@Slf4j
public class PortFinder {

    private PortFinder() {}

    private static final int MAX_PORT_TRIES = 100;
    private static final Random random = new Random();

    public static class ExceededMaxPortAssigmentAttemptException extends Exception {
        public ExceededMaxPortAssigmentAttemptException(Throwable cause) {
            super(cause);
        }
    }

    public static int retryWithNewPortUntilNoThrow(IntConsumer r)
            throws ExceededMaxPortAssigmentAttemptException {
        int numTries = 0;
        while (true) {
            try {
                int port = random.nextInt((2 << 15) - 1025) + 1025;
                r.accept(Integer.valueOf(port));
                return port;
            } catch (Exception e) {
                if (++numTries >= MAX_PORT_TRIES) {
                    log.atError().setCause(e).setMessage(()->"Exceeded max tries {} giving up")
                            .addArgument(MAX_PORT_TRIES).log();
                    throw new ExceededMaxPortAssigmentAttemptException(e);
                }
                log.atWarn().setCause(e).setMessage(()->"Eating exception and trying again").log();
            }
        }
    }

}