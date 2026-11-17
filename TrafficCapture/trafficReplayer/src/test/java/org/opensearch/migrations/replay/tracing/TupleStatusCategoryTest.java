package org.opensearch.migrations.replay.tracing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TupleStatusCategoryTest {
    @ParameterizedTest
    @CsvSource({
        "200, 200",
        "201, 200",
        "304, 300",
        "401, 401",
        "403, 403",
        "404, 404",
        "409, 409",
        "429, 429",
        "400, unclassified_4xx",
        "413, unclassified_4xx",
        "500, 500",
        "503, 500"
    })
    void classesAreBucketedButDistinguishedClientErrorsStayExact(int status, String expected) {
        Assertions.assertEquals(expected, ReplayContexts.TupleHandlingContext.categorizeStatus(status));
    }
}
