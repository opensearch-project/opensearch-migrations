package com.rfs.common;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

public class LuceneDocumentsReader {
    private static final Logger logger = LogManager.getLogger(LuceneDocumentsReader.class);

    public static List<Document> readDocuments(Path luceneFilesBasePath, String indexName, int shardId) throws Exception {
        Path indexDirectoryPath = luceneFilesBasePath.resolve(indexName).resolve(String.valueOf(shardId));

        List<Document> documents = new ArrayList<>();

        try (FSDirectory directory = FSDirectory.open(indexDirectoryPath);
             IndexReader reader = DirectoryReader.open(directory)) {

            // Add all documents to our output that have the _source field set and filled in
            for (int i = 0; i < reader.maxDoc(); i++) {
                Document document = reader.document(i);
                BytesRef source_bytes = document.getBinaryValue("_source");
                String id;
                // TODO Improve handling of missing document id (https://opensearch.atlassian.net/browse/MIGRATIONS-1649)
                try {
                    id = Uid.decodeId(reader.document(i).getBinaryValue("_id").bytes);
                } catch (Exception e) {
                    logger.warn("Unable to parse Document id from Document");
                    id = "unknown-id";
                }
                if (source_bytes == null || source_bytes.bytes.length == 0) { // Skip deleted documents
                    logger.info("Document " + id + " is deleted or doesn't have the _source field enabled");
                    continue;
                }

                documents.add(document);
                logger.info("Document " + id + " read successfully");
            }
        }

        return documents;
    }
}
