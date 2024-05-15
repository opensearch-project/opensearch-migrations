package com.rfs.common;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import lombok.Lombok;
import reactor.core.publisher.Flux;


public class LuceneDocumentsReader {
    private static final Logger logger = LogManager.getLogger(LuceneDocumentsReader.class);

    public Flux<Document> readDocuments(Path luceneFilesBasePath, String indexName, int shardId) {
        Path indexDirectoryPath = luceneFilesBasePath.resolve(indexName).resolve(String.valueOf(shardId));

        return Flux.using(
            () -> openIndexReader(indexDirectoryPath),
            reader -> {
                logger.info(reader.maxDoc() + " documents found in the current Lucene index");

                return Flux.range(0, reader.maxDoc()) // Extract all the Documents in the IndexReader
                .handle((i, sink) -> {
                    Document doc = getDocument(reader, i);
                    if (doc != null) { // Skip malformed docs
                        sink.next(doc);
                    }
                }).cast(Document.class);
            },
            reader -> { // Close the IndexReader when done
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.error("Failed to close IndexReader", e);
                    throw Lombok.sneakyThrow(e);
                }
            }
        );
    }

    protected IndexReader openIndexReader(Path indexDirectoryPath) throws IOException {
        return DirectoryReader.open(FSDirectory.open(indexDirectoryPath));
    }

    protected Document getDocument(IndexReader reader, int docId) {
        try {
            Document document = reader.document(docId);
            BytesRef source_bytes = document.getBinaryValue("_source");
            String id;
            try {
                id = Uid.decodeId(document.getBinaryValue("_id").bytes);
            } catch (Exception e) {
                StringBuilder errorMessage = new StringBuilder();
                errorMessage.append("Unable to parse Document id from Document.  The Document's Fields: ");
                document.getFields().forEach(f -> errorMessage.append(f.name()).append(", "));
                logger.error(errorMessage.toString());
                return null; // Skip documents with missing id
            }
            if (source_bytes == null || source_bytes.bytes.length == 0) {
                logger.warn("Document " + id + " is deleted or doesn't have the _source field enabled");
                return null;  // Skip these too
            }

            logger.debug("Document " + id + " read successfully");
            return document;
        } catch (Exception e) {
            logger.error("Failed to read document at Lucene index location " + docId, e);
            return null;
        }
    }
}
