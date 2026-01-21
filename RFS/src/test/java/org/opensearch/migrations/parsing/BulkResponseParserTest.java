package org.opensearch.migrations.parsing;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;

import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.Streams;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntry;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntryFailure;

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

    @Test
    void testGetFailedPositions_allSuccess() throws IOException {
        var response = BulkRequestGenerator.bulkItemResponse(false, List.of(
            itemEntry("1"), itemEntry("2"), itemEntry("3")
        ));
        var failed = BulkResponseParser.getFailedPositions(response, DocumentExceptionAllowlist.empty());
        assertThat(failed.cardinality(), equalTo(0));
    }

    @Test
    void testGetFailedPositions_allFailed() throws IOException {
        var response = BulkRequestGenerator.bulkItemResponse(true, List.of(
            itemEntryFailure("1"), itemEntryFailure("2"), itemEntryFailure("3")
        ));
        var failed = BulkResponseParser.getFailedPositions(response, DocumentExceptionAllowlist.empty());
        assertThat(failed.cardinality(), equalTo(3));
        assertThat(failed.get(0), equalTo(true));
        assertThat(failed.get(1), equalTo(true));
        assertThat(failed.get(2), equalTo(true));
    }

    @Test
    void testGetFailedPositions_mixed() throws IOException {
        // Position: 0=success, 1=fail, 2=success, 3=fail
        var response = BulkRequestGenerator.bulkItemResponse(true, List.of(
            itemEntry("a"), itemEntryFailure("b"), itemEntry("c"), itemEntryFailure("d")
        ));
        var failed = BulkResponseParser.getFailedPositions(response, DocumentExceptionAllowlist.empty());
        
        assertThat(failed.cardinality(), equalTo(2));
        assertThat(failed.get(0), equalTo(false));
        assertThat(failed.get(1), equalTo(true));
        assertThat(failed.get(2), equalTo(false));
        assertThat(failed.get(3), equalTo(true));
        
        // Verify nextSetBit iteration
        assertThat(failed.nextSetBit(0), equalTo(1));
        assertThat(failed.nextSetBit(2), equalTo(3));
        assertThat(failed.nextSetBit(4), equalTo(-1));
    }

    @Test
    void testGetFailedPositions_firstSuccessLastFail() throws IOException {
        var response = BulkRequestGenerator.bulkItemResponse(true, List.of(
            itemEntry("first"), itemEntryFailure("last")
        ));
        var failed = BulkResponseParser.getFailedPositions(response, DocumentExceptionAllowlist.empty());
        
        assertThat(failed.cardinality(), equalTo(1));
        assertThat(failed.nextSetBit(0), equalTo(1));
    }

    @Test
    void testGetFailedPositions_firstFailLastSuccess() throws IOException {
        var response = BulkRequestGenerator.bulkItemResponse(true, List.of(
            itemEntryFailure("first"), itemEntry("last")
        ));
        var failed = BulkResponseParser.getFailedPositions(response, DocumentExceptionAllowlist.empty());
        
        assertThat(failed.cardinality(), equalTo(1));
        assertThat(failed.nextSetBit(0), equalTo(0));
    }
}
