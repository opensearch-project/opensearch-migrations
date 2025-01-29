package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.Uid;

import lombok.Lombok;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import shadow.lucene9.org.apache.lucene.document.Document;
import shadow.lucene9.org.apache.lucene.index.DirectoryReader;
import shadow.lucene9.org.apache.lucene.index.IndexReader;
import shadow.lucene9.org.apache.lucene.index.LeafReaderContext;
import shadow.lucene9.org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

@RequiredArgsConstructor
@Slf4j
public class LuceneDocumentsReader9 implements LuceneDocumentsReader {

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;
 
    /**
     * There are a variety of states the documents in our Lucene Index can be in; this method extracts those documents
     * that would be considered "live" from the ElasticSearch/OpenSearch perspective.  The most important thing to know is
     * that Lucene segments are immutable.  For additional context, it is highly recommended to read this section of the
     * Lucene docs for a high level overview of the topics involved:
     *
     * https://lucene.apache.org/core/8_0_0/core/org/apache/lucene/codecs/lucene80/package-summary.html
     *
     * When ElasticSearch/OpenSearch deletes a document, it doesn't actually remove it from the Lucene Index.  Instead, what
     * happens is that the document is marked as "deleted" in the Lucene Index, but it is still present in the Lucene segment
     * on disk.  The next time a merge occurs, that segment will be deleted, and the deleted documents in it are thereby
     * removed from the Lucene Index.  A similar thing happens when a document is updated; the old document is marked as
     * "deleted" in the Lucene segment and the new version of the document is added in a new Lucene segment.  Until a merge
     * occurs, both the old and new versions of the document will exist in the Lucene Index in different segments, though only
     * the new version will be returned in search results.  This means that from an ES/OS perspective, you could have a single
     * document that has been created, deleted, recreated, updated, etc. multiple times at the Elasticsearch/OpenSearch level
     * and only a single version of the doc would exist when you queried the ES/OS Index - but every single iteration of that
     * document might still exist in the Lucene segments on disk, all of which have the same _id (from the ES/OS perspective).
     *
     * Additionally, Elasticsearch 7 introduced a feature called "soft deletes" which allows you to mark a document as
     * "deleted" in the Lucene Index without actually removing it from the Lucene Index.  From what I can gather, soft deletes
     * are an optimization to reduce the likelyhood of needing to re-download full shards when a node drops out of the cluster,
     * loses synchronization, and re-joins.  They make it more likely the cluster can just replay the missed operations.  You
     * can read a bit more about soft deletes here:
     *
     * https://www.elastic.co/guide/en/elasticsearch/reference/7.10/index-modules-history-retention.html
     *
     * Soft deletes works by having the application writing the Lucene Index define a field that is used to mark a document as
     * "soft deleted" or not.  When a document is marked as "soft deleted", it is not returned in search results, but it is
     * still present in the Lucene Index.  The status of whether any given document is "soft deleted" or not is stored in the
     * Lucene Index itself.  By default, Elasticsearch 7+ Indices have soft deletes enabled;  this is an Index-level setting.
     * Just like deleted documents and old versions of updated documents, we don't want to reindex them agaisnt the target
     * cluster.
     *
     * In order to retrieve only those documents that would be considered "live" in ES/OS, we use a few tricks:
     * 1. We make sure we use the latest Lucene commit point on the Lucene Index.  A commit is a Lucene abstraction that
     *     comprises a consistent, point-in-time view of the Segments in the Lucene Index.  By default, a DirectoryReader
     *     will use the latest commit point.
     * 2. We use a concept called "liveDocs" to determine if a document is "live" or not.  The liveDocs are a bitset that
     *    is associated with each Lucene segment that tells us if a document is "live" or not.  If a document is hard-deleted
     *    (i.e. deleted from the ES/OS perspective or an old version of an updated doc), then the liveDocs bit for that
     *    document will be false.
     * 3. We wrap our DirectoryReader in a SoftDeletesDirectoryReaderWrapper if it's possible that soft deletes will be
     *    present in the Lucene Index.  This wrapper will filter out documents that are marked as "soft deleted" in the
     *    Lucene Index.

     */
    public Flux<RfsLuceneDocument> readDocuments(int startSegmentIndex, int startDoc) {
        return Flux.using(
            () -> wrapReader(getReader(), softDeletesPossible, softDeletesField),
            reader -> readDocsByLeavesFromStartingPosition(reader, startSegmentIndex, startDoc),
            reader -> {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.atError().setMessage("Failed to close DirectoryReader").setCause(e).log();
                    throw Lombok.sneakyThrow(e);
                }
        });
    }

    protected DirectoryReader getReader() throws IOException {
        try (var directory = FSDirectory.open(indexDirectoryPath)) {
            var commits = DirectoryReader.listCommits(directory);
            var latestCommit = commits.get(commits.size() - 1);

            return DirectoryReader.open(latestCommit);
        }
    }

    /* Start reading docs from a specific segment and document id.
    If the startSegmentIndex is 0, it will start from the first segment.
    If the startDocId is 0, it will start from the first document in the segment.
     */
    Publisher<RfsLuceneDocument> readDocsByLeavesFromStartingPosition(DirectoryReader reader, int startSegmentIndex, int startDocId) {
        var maxDocumentsToReadAtOnce = 100; // Arbitrary value
        log.atInfo().setMessage("{} documents in {} leaves found in the current Lucene index")
            .addArgument(reader::maxDoc)
            .addArgument(() -> reader.leaves().size())
            .log();

        // Create shared scheduler for i/o bound document reading
        var sharedSegmentReaderScheduler = Schedulers.newBoundedElastic(maxDocumentsToReadAtOnce, Integer.MAX_VALUE, "sharedSegmentReader");

        return Flux.fromIterable(reader.leaves())
            .skip(startSegmentIndex)
            .flatMap(ctx -> getReadDocCallablesFromSegments(ctx,
                    // Only use startDocId for the first segment we process
                    ctx.ord == startSegmentIndex ? startDocId : 0))
            .flatMap(c -> Mono.fromCallable(c)
                            .subscribeOn(sharedSegmentReaderScheduler), // Scheduler to read documents on
                    maxDocumentsToReadAtOnce) // Don't need to worry about prefetch before this step as documents aren't realized
            .doOnTerminate(sharedSegmentReaderScheduler::dispose);
    }

    Publisher<Callable<RfsLuceneDocument>> getReadDocCallablesFromSegments(LeafReaderContext leafReaderContext, int startDocId) {
        var segmentReader = leafReaderContext.reader();
        var liveDocs = segmentReader.getLiveDocs();

        return Flux.range(startDocId, segmentReader.maxDoc() - startDocId)
            .subscribeOn(Schedulers.parallel())
            .map(docIdx -> () -> ((liveDocs == null || liveDocs.get(docIdx)) ? // Filter for live docs
                    getDocument(segmentReader, docIdx, true) : // Get document, returns null to skip malformed docs
                    null));
    }

    protected DirectoryReader wrapReader(DirectoryReader reader, boolean softDeletesEnabled, String softDeletesField) throws IOException {
        if (softDeletesEnabled) {
            return new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
        }
        return reader;
    }

    protected RfsLuceneDocument getDocument(IndexReader reader, int docSegId, boolean isLive) {
        Document document;
        try {
            document = reader.document(docSegId);
        } catch (IOException e) {
            log.atError()
                .setCause(e)
                .setMessage("Failed to read document segment id {} from source {}")
                .addArgument(docSegId)
                .addArgument(indexDirectoryPath)
                .log();
            return null;
        }

        String id = null;
        String type = null;
        BytesRef sourceBytes = null;
        String routing = null;

        try {
            for (var field : document.getFields()) {
                String fieldName = field.name();
                switch (fieldName) {
                    case "_id": {
                        // ES 6+
                        var idBytes = field.binaryValue();
                        id = Uid.decodeId(idBytes.bytes);
                        break;
                    }
                    case "_uid": {
                        // ES <= 6
                        var combinedTypeId = field.stringValue().split("#", 2);
                        type = combinedTypeId[0];
                        id = combinedTypeId[1];
                        break;
                    }
                    case "_source": {
                        // All versions (?)
                        sourceBytes = field.binaryValue();
                        break;
                    }
                    case "_routing": {
                        routing = field.stringValue();
                        break;
                    }
                    default:
                        break;
                }
            }
            if (id == null) {
                log.atWarn().setMessage("Skipping document segment id {} from source {}, it does not have an referenceable id.")
                    .addArgument(docSegId)
                    .addArgument(indexDirectoryPath)
                    .log();
                return null;  // Skip documents with missing id
            }

            if (sourceBytes == null || sourceBytes.bytes.length == 0) {
                log.atWarn().setMessage("Skipping document segment id {} document id {} from source {}, it doesn't have the _source field enabled.")
                    .addArgument(docSegId)
                    .addArgument(id)
                    .addArgument(indexDirectoryPath)
                    .log();
                return null;  // Skip these
            }
        } catch (RuntimeException e) {
            StringBuilder errorMessage = new StringBuilder();
            errorMessage.append("Unable to parse Document id from Document.  The Document's Fields: ");
            document.getFields().forEach(f -> errorMessage.append(f.name()).append(", "));
            log.atError().setCause(e).setMessage("{}").addArgument(errorMessage).log();
            return null; // Skip documents with invalid id
        }

        if (!isLive) {
            log.atDebug().setMessage("Document {} is not live").addArgument(id).log();
            return null; // Skip these
        }

        log.atDebug().setMessage("Document id {} from source {} read successfully.")
            .addArgument(id)
            .addArgument(indexDirectoryPath)
            .log();
        return new RfsLuceneDocument(id, type, sourceBytes.utf8ToString(), routing);
    }
}
