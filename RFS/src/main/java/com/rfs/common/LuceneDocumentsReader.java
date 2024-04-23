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
import reactor.core.publisher.Flux;

public class LuceneDocumentsReader {
    private static final Logger logger = LogManager.getLogger(LuceneDocumentsReader.class);

    public static Flux<Document> readDocuments(Path luceneFilesBasePath, String indexName, int shardId) {
        Path indexDirectoryPath = luceneFilesBasePath.resolve(indexName).resolve(String.valueOf(shardId));

        return Flux.using(
            () -> DirectoryReader.open(FSDirectory.open(indexDirectoryPath)),
            reader -> {
                logger.info(reader.maxDoc() + " documents found in the current Lucene index");

                return Flux.range(0, reader.maxDoc()) // Extract all the Documents in the IndexReader
                           .map(i -> getDocument(reader, i))
                           .cast(Document.class);
            },
            reader -> { // Close the IndexReader when done
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.error("Failed to close IndexReader", e);
                }
            }
        ).filter(doc -> doc != null); // Skip docs that failed to read
    }

    private static Document getDocument(IndexReader reader, int docId) {
        try {
            Document document = reader.document(docId);
            BytesRef source_bytes = document.getBinaryValue("_source");
            String id;
            try {
                id = Uid.decodeId(reader.document(docId).getBinaryValue("_id").bytes);
            } catch (Exception e) {
                StringBuilder errorMessage = new StringBuilder();
                errorMessage.append("Unable to parse Document id from Document.  The Document's Fields: ");
                document.getFields().forEach(f -> errorMessage.append(f.name()).append(", "));
                logger.error(errorMessage.toString());
                return null; // Skip documents with missing id
            }
            if (source_bytes == null || source_bytes.bytes.length == 0) {
                logger.warn("Document " + id + " is deleted or doesn't have the _source field enabled");
                return null;  // Skip deleted documents or those without the _source field
            }

            logger.debug("Document " + id + " read successfully");
            return document;
        } catch (Exception e) {
            logger.error("Failed to read document at Lucene index location " + docId, e);
            return null;
        }
    }
}
