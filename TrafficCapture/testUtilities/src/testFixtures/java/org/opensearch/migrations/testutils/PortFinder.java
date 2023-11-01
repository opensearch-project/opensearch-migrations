package org.opensearch.migrations.testutils;


import java.util.Random;
import java.util.function.Consumer;

/**
 * Helper class to keep retrying ports against a Consumer until the
 * Consumer doesn't throw an exception.
 */
public class PortFinder {

    private static final int MAX_PORT_TRIES = 100;
    private static final Random random = new Random();

    public static class ExceededMaxPortAssigmentAttemptException extends Exception {}

    public static int retryWithNewPortUntilNoThrow(Consumer<Integer> r)
            throws ExceededMaxPortAssigmentAttemptException {
        int numTries = 0;
        while (true) {
            try {
                int port = (Math.abs(random.nextInt()) % (2 ^ 16 - 1025)) + 1025;
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