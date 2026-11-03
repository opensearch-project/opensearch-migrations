package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpRetryClassifierTest {
    @ParameterizedTest
    @CsvSource({
        "408, true", "429, true", "500, true", "502, true", "503, true", "504, true",
        "400, false", "401, false", "403, false", "404, false", "413, false",
        // A status a caller didn't expect but that isn't an error is retried, as before.
        "200, true", "302, true",
    })
    void retriesTransientStatusesOnly(int status, boolean retryable) {
        Assertions.assertEquals(retryable, HttpRetryClassifier.isRetryableStatus(status));
        var failure = new ClusterVersionDetector.UnexpectedStatusCode(new HttpResponse(status, "", Map.of(), ""));
        Assertions.assertEquals(retryable, HttpRetryClassifier.isRetryable(failure));
    }

    @Test
    void retriesFailuresWithoutAStatus() {
        Assertions.assertTrue(HttpRetryClassifier.isRetryable(new IOException("Connection reset")));
        Assertions.assertEquals("IOException: Connection reset",
            HttpRetryClassifier.describe(new IOException("Connection reset")));
        Assertions.assertEquals("status 429", HttpRetryClassifier.describe(
            new ClusterVersionDetector.UnexpectedStatusCode(new HttpResponse(429, "", Map.of(), ""))));
    }
}
