package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailedDocumentStreamCursorTest {

    @Test
    void roundTripsThroughItsEncoding() {
        var cursor = new FailedDocumentStreamCursor("prefix/session=s/index=i/worker=w/file-1.ndjson.gz", 42);

        assertThat(FailedDocumentStreamCursor.decode(cursor.encode()), equalTo(cursor));
    }

    @Test
    void keepsAKeyThatContainsTheDelimiter() {
        // S3 permits ':' in a key, and the key is the remainder of the cursor rather than a
        // delimited field, so it needs no escaping.
        var cursor = new FailedDocumentStreamCursor("index=a:b/worker=c:d/file-1.ndjson.gz", 7);

        var decoded = FailedDocumentStreamCursor.decode(cursor.encode());

        assertThat(decoded.objectKey(), equalTo("index=a:b/worker=c:d/file-1.ndjson.gz"));
        assertThat(decoded.ordinal(), equalTo(7L));
    }

    @Test
    void rejectsACursorMintedBySomeOtherSource() {
        // The tag is what stops a Lucene or delta cursor being read as a position in this stream.
        assertThrows(IllegalArgumentException.class, () -> FailedDocumentStreamCursor.decode("lucene:12"));
        assertThrows(IllegalArgumentException.class, () -> FailedDocumentStreamCursor.decode("12"));
        assertThrows(IllegalArgumentException.class, () -> FailedDocumentStreamCursor.decode(null));
    }

    @Test
    void rejectsAMalformedOrdinal() {
        assertThrows(IllegalArgumentException.class, () -> FailedDocumentStreamCursor.decode("fds:1:abc:some/key"));
        assertThrows(IllegalArgumentException.class, () -> FailedDocumentStreamCursor.decode("fds:1:nokey"));
    }

    @Test
    void rejectsANegativeOrdinal() {
        assertThrows(IllegalArgumentException.class, () -> new FailedDocumentStreamCursor("k", -1));
    }
}
