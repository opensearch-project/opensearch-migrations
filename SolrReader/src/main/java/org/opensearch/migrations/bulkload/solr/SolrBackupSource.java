package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;
import org.opensearch.migrations.bulkload.lucene.SegmentNameSorter;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;
import org.opensearch.migrations.bulkload.pipeline.ir.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.ir.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link DocumentSource} that reads documents from a Solr backup directory
 * containing raw Lucene index files, with schema-derived mappings.
 *
 * <p>Requires both a backup directory (Lucene files) and a Solr schema
 * (JSON from {@code /schema?wt=json} or the {@code managed-schema} file).
 * The schema is always converted to OpenSearch mappings via {@link SolrSchemaConverter}.
 */
@Slf4j
public class SolrBackupSource implements DocumentSource {

    private final Path backupDir;
    private final String collectionName;
    private final JsonNode solrSchema;

    /**
     * @param backupDir      path to the Solr backup directory containing Lucene index files
     * @param collectionName the name to use for this collection in the target
     * @param solrSchema     Solr schema JSON (the "schema" object from /schema?wt=json response)
     */
    public SolrBackupSource(Path backupDir, String collectionName, JsonNode solrSchema) {
        this.backupDir = backupDir;
        this.collectionName = collectionName;
        this.solrSchema = solrSchema;
    }

    @Override
    public List<String> listCollections() {
        return List.of(collectionName);
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        return List.of(new SolrShardPartition(collectionName, "shard1"));
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        var fields = solrSchema.path("fields");
        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields);
        log.info("Converted Solr schema to OpenSearch mappings: {} fields", mappings.path("properties").size());
        return new CollectionMetadata(collectionName, 1, Map.of(
            CollectionMetadata.ES_MAPPINGS, mappings
        ));
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        try {
            var reader = new IndexReader9(backupDir, false, null);
            var segmentsFile = findSegmentsFile();
            var directoryReader = reader.getReader(segmentsFile);

            log.info("Reading Solr backup: {} docs in {} segments from {}",
                directoryReader.maxDoc(), directoryReader.leaves().size(), backupDir);

            var scheduler = Schedulers.newBoundedElastic(10, Integer.MAX_VALUE, "solrReader");

            var sortedLeaves = directoryReader.leaves().stream()
                .map(LuceneLeafReaderContext::reader)
                .sorted(SegmentNameSorter.INSTANCE)
                .toList();

            int[] docBases = new int[sortedLeaves.size()];
            for (int i = 1; i < sortedLeaves.size(); i++) {
                docBases[i] = docBases[i - 1] + sortedLeaves.get(i - 1).maxDoc();
            }

            return Flux.range(0, sortedLeaves.size())
                .concatMap(segIdx -> {
                    var segReader = sortedLeaves.get(segIdx);
                    int segDocBase = docBases[segIdx];
                    var liveDocs = segReader.getLiveDocs();
                    var liveDocStream = (liveDocs != null)
                        ? liveDocs.stream()
                        : IntStream.range(0, segReader.maxDoc());

                    return Flux.fromStream(liveDocStream.boxed())
                        .flatMapSequential(docIdx -> Mono.fromCallable(() ->
                            SolrLuceneDocReader.getDocument(
                                segReader, docIdx, true, segDocBase,
                                DocumentChangeType.INDEX
                            )
                        ).subscribeOn(scheduler), 10)
                        .filter(Objects::nonNull);
                })
                .skip(startingDocOffset)
                .map(SolrBackupSource::toDocument)
                .doFinally(s -> scheduler.dispose());
        } catch (IOException e) {
            return Flux.error(new RuntimeException("Failed to open Solr backup: " + backupDir, e));
        }
    }

    private String findSegmentsFile() {
        try (var stream = Files.list(backupDir)) {
            return stream
                .map(p -> p.getFileName().toString())
                .filter(name -> name.startsWith("segments_"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No segments_N file found in Solr backup: " + backupDir));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list Solr backup directory: " + backupDir, e);
        }
    }

    static Document toDocument(LuceneDocumentChange change) {
        var id = change.getId();
        if (id == null || id.isEmpty()) {
            id = "solr_doc_" + change.getLuceneDocNumber();
        }
        byte[] source = change.getSource();
        if (source == null) {
            source = "{}".getBytes(StandardCharsets.UTF_8);
        }
        return new Document(id, source, Document.Operation.UPSERT, Map.of(), Map.of());
    }
}
