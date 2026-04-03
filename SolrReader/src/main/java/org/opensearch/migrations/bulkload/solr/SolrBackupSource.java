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
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@link DocumentSource} that reads documents from a Solr backup directory
 * containing raw Lucene index files, with schema-derived mappings.
 *
 * <p>Supports both single-shard backups (flat directory with segments_N) and
 * multi-shard SolrCloud backups (subdirectories per shard, each containing
 * a {@code data/index/} with segments_N).
 */
@Slf4j
public class SolrBackupSource implements DocumentSource {

    private static final String INDEX_DIR_NAME = "index";

    private final Path backupDir;
    private final String collectionName;
    private final JsonNode solrSchema;

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
        var shardDirs = discoverShardDirs();
        return shardDirs.stream()
            .map(dir -> (Partition) new SolrShardPartition(collectionName, dir.getFileName().toString(), dir))
            .toList();
    }

    @Override
    public CollectionMetadata readCollectionMetadata(String collectionName) {
        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(
            solrSchema.path("fields"),
            solrSchema.path("dynamicFields"),
            solrSchema.path("copyFields"),
            solrSchema.path("fieldTypes")
        );
        var partitionCount = listPartitions(collectionName).size();
        log.info("Converted Solr schema to OpenSearch mappings: {} fields, {} shards",
            mappings.path("properties").size(), partitionCount);
        return new CollectionMetadata(collectionName, partitionCount, Map.of(
            CollectionMetadata.ES_MAPPINGS, mappings
        ));
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        var solrPartition = (SolrShardPartition) partition;
        var indexDir = solrPartition.indexPath();
        return readLuceneIndex(indexDir, startingDocOffset);
    }

    /**
     * Discover shard directories from the backup. Supports:
     * <ol>
     *   <li>Flat directory: segments_N at top level → single shard</li>
     *   <li>SolrCloud backup: shard1/data/index/, shard2/data/index/, etc.</li>
     * </ol>
     */
    List<Path> discoverShardDirs() {
        // Check if this is a flat backup (segments_N at top level)
        if (hasSegmentsFile(backupDir)) {
            return List.of(backupDir);
        }

        // Check for S3 backup structure: index/ directory at top level
        var indexDir = backupDir.resolve(INDEX_DIR_NAME);
        if (hasSegmentsFile(indexDir)) {
            return List.of(indexDir);
        }

        // Look for SolrCloud shard structure: shardN/data/index/
        try (var dirs = Files.list(backupDir)) {
            var shardDirs = dirs
                .filter(Files::isDirectory)
                .sorted()
                .flatMap(shardDir -> {
                    // Direct: shardN/ contains segments_N
                    if (hasSegmentsFile(shardDir)) {
                        return java.util.stream.Stream.of(shardDir);
                    }
                    // SolrCloud: shardN/data/index/ contains segments_N
                    var indexPath = shardDir.resolve("data").resolve(INDEX_DIR_NAME);
                    if (hasSegmentsFile(indexPath)) {
                        return java.util.stream.Stream.of(indexPath);
                    }
                    return java.util.stream.Stream.empty();
                })
                .toList();

            if (!shardDirs.isEmpty()) {
                log.info("Discovered {} shard(s) in backup: {}", shardDirs.size(), backupDir);
                return shardDirs;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list backup directory: " + backupDir, e);
        }

        // Fallback: treat entire directory as single shard
        return List.of(backupDir);
    }

    private static boolean hasSegmentsFile(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith("segments_"));
        } catch (IOException e) {
            return false;
        }
    }

    private Flux<Document> readLuceneIndex(Path indexDir, long startingDocOffset) {
        try {
            var reader = new IndexReader9(indexDir, false, null);
            var segmentsFile = findSegmentsFile(indexDir);
            var directoryReader = reader.getReader(segmentsFile);

            log.info("Reading Solr backup: {} docs in {} segments from {}",
                directoryReader.maxDoc(), directoryReader.leaves().size(), indexDir);

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
            return Flux.error(new RuntimeException("Failed to open Solr backup: " + indexDir, e));
        }
    }

    private static String findSegmentsFile(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                .map(p -> p.getFileName().toString())
                .filter(name -> name.startsWith("segments_"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No segments_N file found in: " + dir));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list directory: " + dir, e);
        }
    }

    /**
     * Renames UUID-named files in a Solr S3 backup to their original Lucene filenames
     * using the shard_backup_metadata mapping files.
     *
     * <p>Solr's S3 backup repository stores index files with UUID names and keeps
     * a mapping in {@code shard_backup_metadata/md_shardN_0.json}. This method
     * reads those mappings and renames the files in the {@code index/} directory
     * so that standard Lucene readers can open them.
     */
    public static void restoreFileNames(Path backupDir) throws IOException {
        var metadataDir = backupDir.resolve("shard_backup_metadata");
        if (!Files.isDirectory(metadataDir)) {
            log.debug("No shard_backup_metadata directory found in {}, skipping rename", backupDir);
            return;
        }
        var mapper = new ObjectMapper();
        try (var mdFiles = Files.list(metadataDir)) {
            mdFiles.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(mdFile -> {
                try {
                    var tree = mapper.readTree(mdFile.toFile());
                    var indexDir = backupDir.resolve(INDEX_DIR_NAME);
                    tree.fields().forEachRemaining(entry -> {
                        var uuid = entry.getKey();
                        var fileName = entry.getValue().path("fileName").asText(null);
                        if (fileName == null) return;
                        var src = indexDir.resolve(uuid);
                        var dst = indexDir.resolve(fileName);
                        if (Files.exists(src) && !Files.exists(dst)) {
                            try {
                                Files.move(src, dst);
                            } catch (IOException e) {
                                log.warn("Failed to rename {} to {}", src, dst, e);
                            }
                        }
                    });
                    log.info("Restored Lucene filenames from {}", mdFile.getFileName());
                } catch (IOException e) {
                    log.warn("Failed to read shard metadata: {}", mdFile, e);
                }
            });
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
