package org.opensearch.migrations.bulkload.common;

/**
 * Which failed requests are worth retrying, shared by the checks RFS makes against a cluster before it starts.
 *
 * <p>A request that got no response (a timeout, a reset, a refused connection) may succeed next time, and so may
 * one answered with a 5xx, 408 (request timeout) or 429 (too many requests). Any other 4xx (400, 401, 403, 404,
 * ...) means the request or the credentials are wrong, and repeating it only delays the error.
 */
public final class HttpRetryClassifier {
    private static final int FIRST_CLIENT_ERROR_STATUS = 400;
    private static final int REQUEST_TIMEOUT_STATUS = 408;
    private static final int TOO_MANY_REQUESTS_STATUS = 429;
    private static final int FIRST_SERVER_ERROR_STATUS = 500;

    private HttpRetryClassifier() {}

    /** A failure caused by the HTTP status a request was answered with. */
    public interface StatusCodeFailure {
        int statusCode();
    }

    public static boolean isRetryableStatus(int statusCode) {
        boolean clientError =
            statusCode >= FIRST_CLIENT_ERROR_STATUS && statusCode < FIRST_SERVER_ERROR_STATUS;
        return !clientError
            || statusCode == REQUEST_TIMEOUT_STATUS
            || statusCode == TOO_MANY_REQUESTS_STATUS;
    }

    /** For a {@code Retry} filter: everything is retried except a {@link StatusCodeFailure} with a non-retryable status. */
    public static boolean isRetryable(Throwable failure) {
        return !(failure instanceof StatusCodeFailure statusFailure) || isRetryableStatus(statusFailure.statusCode());
    }

    /** "status 429", or the exception's class and message when no response arrived. */
    public static String describe(Throwable failure) {
        if (failure instanceof StatusCodeFailure statusFailure) {
            return "status " + statusFailure.statusCode();
        }
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
