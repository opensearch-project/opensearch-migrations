package org.opensearch.migrations.testutils;


import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Helper class to keep retrying ports against a Consumer until the
 * Consumer doesn't throw an exception.
 */
public class PortFinder {

    private PortFinder() {}

    private static final int MAX_PORT_TRIES = 100;
    private static final Random random = new Random();

    public static class ExceededMaxPortAssigmentAttemptException extends Exception {}

    public static int retryWithNewPortUntilNoThrow(IntConsumer r)
            throws ExceededMaxPortAssigmentAttemptException {
        int numTries = 0;
        while (true) {
            try {
                int port = random.nextInt((2 << 15) - 1025) + 1025;
                r.accept(Integer.valueOf(port));
                return port;
            } catch (Exception e) {
                System.err.println("Exception: "+e);
                e.printStackTrace();
                if (++numTries <= MAX_PORT_TRIES) {
                    throw new ExceededMaxPortAssigmentAttemptException();
                }
            }
        }
    }

}