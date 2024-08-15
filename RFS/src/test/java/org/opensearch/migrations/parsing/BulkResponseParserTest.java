package org.opensearch.migrations.parsing;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.rfs.http.BulkRequestGenerator;
import org.testcontainers.shaded.com.google.common.collect.Streams;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static com.rfs.http.BulkRequestGenerator.itemEntry;
import static com.rfs.http.BulkRequestGenerator.itemEntryFailure;

class BulkResponseParserTest {

    @Test
    void testFindSuccessDocs() throws IOException {
        var successfulIds = List.of("12", "23", "43");
        var failedIds = List.of("76", "65", "88");

        var allEntries = Streams.concat(
            successfulIds.stream().map(id -> itemEntry(id)),
            failedIds.stream().map(id -> itemEntryFailure(id))
        ).collect(Collectors.toList());

        var bulkResponse = BulkRequestGenerator.bulkItemResponse(true, allEntries);

        var result = BulkResponseParser.findSuccessDocs(bulkResponse);

        assertThat(result, equalTo(successfulIds));
    }

    @Test
    void testFindSuccessDocs_truncatedResponse() throws IOException {
        var successDocId = "223";
        var bulkResponse = BulkRequestGenerator.bulkItemResponse(true, List.of(
            itemEntryFailure("failed"),
            itemEntry(successDocId)
        ));

        var boundaryWhenParsable = 1016; // Found by running the test 
        var bulkResponseNotEnoughToParse = bulkResponse.substring(0, boundaryWhenParsable);
        System.err.println("***\n" + bulkResponseNotEnoughToParse + "\n***");
        var result = BulkResponseParser.findSuccessDocs(bulkResponseNotEnoughToParse);
        assertThat(result, empty());

        for (int i = boundaryWhenParsable + 1; i < bulkResponse.length(); i++) {
            var trimmedResponse = bulkResponse.substring(0, i);
            assertThat(BulkResponseParser.findSuccessDocs(trimmedResponse), equalTo(List.of(successDocId)));
        }
    }
}
