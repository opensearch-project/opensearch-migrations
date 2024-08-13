package com.rfs.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

import lombok.Lombok;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@Slf4j
public class LuceneDocumentsReader {
    public static Function<Path, LuceneDocumentsReader> getFactory(boolean softDeletesPossible, String softDeletesField) {
        return path -> new LuceneDocumentsReader(path, softDeletesPossible, softDeletesField);
    }
    public static final int NUM_DOCUMENTS_BUFFERED = 1024;

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;

    public Flux<Document> readDocuments() {
        return Flux.using(() -> wrapReader(DirectoryReader.open(FSDirectory.open(indexDirectoryPath)), softDeletesPossible, softDeletesField), reader -> {
            log.atInfo().log(reader.maxDoc() + " documents found in the current Lucene index");

            return Flux.fromIterable(reader.leaves()) // Iterate over each segment
                .flatMap(leaf -> {
                    LeafReader leafReader = leaf.reader();
                    Bits liveDocs = leafReader.getLiveDocs(); // Get live docs bits

                    return Flux.range(0, leafReader.maxDoc())
                        .handle((docId, sink) -> {
                            // Check if the document is live (not hard deleted/updated)
                            boolean isLive = liveDocs == null || liveDocs.get(docId);
                            Document doc = getDocument(leafReader, docId, isLive);

                            if (doc != null) { // Skip malformed docs
                                sink.next(doc);
                            }
                        });
                })
                .cast(Document.class);
        }, reader -> { // Close the DirectoryReader when done
            try {
                reader.close();
            } catch (IOException e) {
                log.atError().setMessage("Failed to close DirectoryReader").setCause(e).log();
                throw Lombok.sneakyThrow(e);
            }
        });
    }

    protected DirectoryReader wrapReader(DirectoryReader reader, boolean softDeletesEnabled, String softDeletesField) throws IOException {
        if (softDeletesEnabled) {
            return new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
        }
        return reader;
    }

    protected Document getDocument(IndexReader reader, int docId, boolean isLive) {
        try {
            Document document = reader.document(docId);
            BytesRef sourceBytes = document.getBinaryValue("_source");
            String id;
            try {
                id = Uid.decodeId(document.getBinaryValue("_id").bytes);
                log.atDebug().log("Reading document " + id);
            } catch (Exception e) {
                StringBuilder errorMessage = new StringBuilder();
                errorMessage.append("Unable to parse Document id from Document.  The Document's Fields: ");
                document.getFields().forEach(f -> errorMessage.append(f.name()).append(", "));
                log.atError().setMessage(errorMessage.toString()).setCause(e).log();
                return null; // Skip documents with missing id
            }

            if (!isLive) {
                log.atDebug().log("Document " + id + " is not live");
                return null; // Skip these
            }

            if (sourceBytes == null || sourceBytes.bytes.length == 0) {
                log.warn("Document " + id + " doesn't have the _source field enabled");
                return null;  // Skip these
            }

            log.atDebug().log("Document " + id + " read successfully");
            return document;
        } catch (Exception e) {
            log.atError().setMessage("Failed to read document at Lucene index location " + docId).setCause(e).log();
            return null;
        }
    }
}