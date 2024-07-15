package com.rfs.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

class TestLuceneDocumentsReader extends LuceneDocumentsReader {
    public TestLuceneDocumentsReader(Path luceneFilesBasePath) {
        super(luceneFilesBasePath);
    }

    // Helper method to correctly encode the Document IDs for test
    public static byte[] encodeUtf8Id(String id) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[idBytes.length + 1];
        encoded[0] = (byte) Uid.UTF8;
        System.arraycopy(idBytes, 0, encoded, 1, idBytes.length);
        return encoded;
    }

    // Override to return a mocked IndexReader
    @Override
    protected IndexReader openIndexReader(Path indexDirectoryPath) throws IOException {
        // Set up our test docs
        Document doc1 = new Document();
        doc1.add(new StringField("_id", new BytesRef(encodeUtf8Id("id1")), Field.Store.YES));
        doc1.add(new StoredField("_source", new BytesRef("source1")));

        Document doc2 = new Document();
        doc2.add(new StringField("_id", new BytesRef(encodeUtf8Id("id2")), Field.Store.YES));
        doc2.add(new StoredField("_source", new BytesRef("source2")));

        Document doc3 = new Document();
        doc3.add(new StringField("_id", new BytesRef(encodeUtf8Id("id3")), Field.Store.YES));
        doc3.add(new StoredField("_source", new BytesRef("source3")));

        Document doc4 = new Document(); // Doc w/ no fields

        Document doc5 = new Document(); // Doc w/ missing _source
        doc5.add(new StringField("_id", new BytesRef(encodeUtf8Id("id5")), Field.Store.YES));

        // Set up our mock reader
        IndexReader mockReader = mock(IndexReader.class);
        when(mockReader.maxDoc()).thenReturn(5);
        when(mockReader.document(0)).thenReturn(doc1);
        when(mockReader.document(1)).thenReturn(doc2);
        when(mockReader.document(2)).thenReturn(doc3);
        when(mockReader.document(3)).thenReturn(doc4);
        when(mockReader.document(4)).thenReturn(doc5);

        return mockReader;
    }
}

public class LuceneDocumentsReaderTest {
    @Test
    void ReadDocuments_AsExpected() {
        // Use the TestLuceneDocumentsReader to get the mocked documents
        Flux<Document> documents = new TestLuceneDocumentsReader(Paths.get("/fake/path/testIndex/0")).readDocuments();

        // Verify that the results are as expected
        StepVerifier.create(documents).expectNextMatches(doc -> {
            String testId = Uid.decodeId(doc.getBinaryValue("_id").bytes);
            String testSource = doc.getBinaryValue("_source").utf8ToString();
            return "id1".equals(testId) && "source1".equals(testSource);
        }).expectNextMatches(doc -> {
            String testId = Uid.decodeId(doc.getBinaryValue("_id").bytes);
            String testSource = doc.getBinaryValue("_source").utf8ToString();
            return "id2".equals(testId) && "source2".equals(testSource);
        }).expectNextMatches(doc -> {
            String testId = Uid.decodeId(doc.getBinaryValue("_id").bytes);
            String testSource = doc.getBinaryValue("_source").utf8ToString();
            return "id3".equals(testId) && "source3".equals(testSource);
        }).expectComplete().verify();
    }
}
