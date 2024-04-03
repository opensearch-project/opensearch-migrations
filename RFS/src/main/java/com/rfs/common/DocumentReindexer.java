package com.rfs.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.document.Document;


public class DocumentReindexer {
    private static final Logger logger = LogManager.getLogger(DocumentReindexer.class);

    public static void reindex(String indexName, Document document, ConnectionDetails targetConnection) throws Exception {        
        // Get the document details
        String id = Uid.decodeId(document.getBinaryValue("_id").bytes);
        String source = document.getBinaryValue("_source").utf8ToString();

        logger.info("Reindexing document - Index: " + indexName + ", Document ID: " + id);

        // Assemble the request details
        String path = indexName + "/_doc/" + id;
        String body = source;

        // Send the request
        RestClient client = new RestClient(targetConnection);
        client.put(path, body, false);
    }
}
