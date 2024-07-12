package com.rfs.common;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import lombok.Lombok;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@Slf4j
public class LuceneDocumentsReader {
    public static final int NUM_DOCUMENTS_BUFFERED = 1024;
    protected final Path indexDirectoryPath;

    public Flux<Document> readDocuments() {
        return Flux.using(() -> openIndexReader(indexDirectoryPath), reader -> {
            log.info(reader.maxDoc() + " documents found in the current Lucene index");

            return Flux.range(0, reader.maxDoc()) // Extract all the Documents in the IndexReader
                .handle((i, sink) -> {
                    Document doc = getDocument(reader, i);
                    if (doc != null) { // Skip malformed docs
                        sink.next(doc);
                    }
                })
                .cast(Document.class);
        }, reader -> { // Close the IndexReader when done
            try {
                reader.close();
            } catch (IOException e) {
                log.error("Failed to close IndexReader", e);
                throw Lombok.sneakyThrow(e);
            }
        });
    }

    protected IndexReader openIndexReader(Path indexDirectoryPath) throws IOException {
        return DirectoryReader.open(FSDirectory.open(indexDirectoryPath));
    }

    protected Document getDocument(IndexReader reader, int docId) {
        try {
            Document document = reader.document(docId);
            BytesRef sourceBytes = document.getBinaryValue("_source");
            String id;
            try {
                id = Uid.decodeId(document.getBinaryValue("_id").bytes);
            } catch (Exception e) {
                StringBuilder errorMessage = new StringBuilder();
                errorMessage.append("Unable to parse Document id from Document.  The Document's Fields: ");
                document.getFields().forEach(f -> errorMessage.append(f.name()).append(", "));
                log.error(errorMessage.toString());
                return null; // Skip documents with missing id
            }
            if (sourceBytes == null || sourceBytes.bytes.length == 0) {
                log.warn("Document " + id + " is deleted or doesn't have the _source field enabled");
                return null;  // Skip these too
            }

            log.debug("Document " + id + " read successfully");
            return document;
        } catch (Exception e) {
            log.error("Failed to read document at Lucene index location " + docId, e);
            return null;
        }
    }
}
