package com.rfs.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import lombok.Lombok;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@Slf4j
public class LuceneDocumentsReader {
    public static final int NUM_DOCUMENTS_BUFFERED = 1024;
    protected final Path luceneFilesBasePath;

    public class DocumentOrDone {}
    @Getter
    @AllArgsConstructor
    public class DocumentHolder extends DocumentOrDone {
        Document value;
    }
    public class Done extends DocumentOrDone {}

    public BlockingQueue<DocumentOrDone> packageDocumentsIntoQueue(String indexName, int shardId) {
        Path indexDirectoryPath = luceneFilesBasePath.resolve(indexName).resolve(String.valueOf(shardId));
        var docQueue = new ArrayBlockingQueue<DocumentOrDone>(NUM_DOCUMENTS_BUFFERED, true);
        new Thread(() -> {
            try (var reader = openIndexReader(indexDirectoryPath)) {
                log.info(reader.maxDoc() + " documents found in the current Lucene index");
                for (var i=0; i<reader.maxDoc(); ++i) {
                    var doc = getDocument(reader, i);
                    if (doc != null) { // Skip malformed docs
                        docQueue.add(new DocumentHolder(doc));
                    }
                }
                docQueue.add(new Done());
            } catch (IOException e) {
                throw Lombok.sneakyThrow(e);
            }
        }).start();
        return docQueue;
    }

    public Flux<Document> readDocuments(String indexName, int shard) {
        var queue = packageDocumentsIntoQueue(indexName, shard);
        return Flux.create(sink -> {
            while (true) {
                try {
                    var item = queue.take(); // TODO - this is still a blocking call!
                    if (item instanceof Done) {
                        sink.complete();
                        break;
                    }
                    sink.next(((DocumentHolder)item).value);
                } catch (InterruptedException e) {
                    sink.error(e);
                    Thread.currentThread().interrupt();
                    break;
                }

                if (queue.isEmpty() && Thread.currentThread().isInterrupted()) {

                    break;
                }
            }
        });
    }

    @SneakyThrows
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
                log.error(errorMessage.toString());
                return null; // Skip documents with missing id
            }
            if (source_bytes == null || source_bytes.bytes.length == 0) {
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
