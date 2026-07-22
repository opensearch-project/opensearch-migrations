package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.lucene.FieldMappingContext;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;
import org.opensearch.migrations.bulkload.lucene.version_6.IndexReader6;
import org.opensearch.migrations.bulkload.lucene.version_7.IndexReader7;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;
import org.opensearch.migrations.bulkload.lucene.version_9.MappedDirectory;
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
import shadow.lucene9.org.apache.lucene.store.FSDirectory;

/**
 * {@link DocumentSource} that reads documents from a Solr backup directory.
 *
 * <p>Supports three backup layouts:
 * <ul>
 *   <li>Flat: segments_N at top level (standalone replication backup)</li>
 *   <li>SolrCloud with shard dirs: shardN/data/index/segments_N</li>
 *   <li>SolrCloud with UUID files: index/ + shard_backup_metadata/ (read via {@link MappedDirectory})</li>
 * </ul>
 *
 * <p>For the UUID layout, no file renaming or directory creation is needed.
 * The shard metadata maps logical Lucene filenames to physical UUIDs, and
 * {@link MappedDirectory} translates at read time.
 */
@Slf4j
public class SolrBackupSource implements DocumentSource {

    private static final String INDEX_DIR_NAME = "index";
    private static final String SEGMENTS_FILE_PREFIX = "segments_";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path backupDir;
    private final String collectionName;
    private final JsonNode solrSchema;
    private final int solrMajorVersion;
    private final FieldMappingContext mappingContext;

    public SolrBackupSource(Path backupDir, String collectionName, JsonNode solrSchema, int solrMajorVersion) {
        this.backupDir = backupDir;
        this.collectionName = collectionName;
        this.solrSchema = solrSchema;
        this.solrMajorVersion = solrMajorVersion;
        this.mappingContext = buildMappingContext(solrSchema);
    }

    private static FieldMappingContext buildMappingContext(JsonNode schema) {
        if (schema == null) return null;
        var osMappings = SolrSchemaConverter.convertToOpenSearchMappings(
            schema.path("fields"), schema.path("dynamicFields"),
            schema.path("copyFields"), schema.path("fieldTypes"));
        return new FieldMappingContext(osMappings);
    }

    /**
     * Selects the Lucene reader matching the Solr source version:
     * Solr 6 → Lucene 6, Solr 7 → Lucene 7, Solr 8/9 → Lucene 9 (8 via backward-codecs).
     */
    private LuceneIndexReader newLuceneReader(Path indexDir) {
        return switch (solrMajorVersion) {
            case 6 -> new IndexReader6(indexDir);
            case 7 -> new IndexReader7(indexDir, false, null);
            case 8, 9 -> new IndexReader9(indexDir, false, null);
            default -> throw new IllegalArgumentException(
                "Unsupported Solr major version: " + solrMajorVersion + " (supported: 6, 7, 8, 9)");
        };
    }

    @Override
    public List<String> listCollections() {
        return List.of(collectionName);
    }

    @Override
    public List<Partition> listPartitions(String collectionName) {
        // Check for SolrCloud UUID backup: shard_backup_metadata/ + index/
        var shardMappings = parseShardMappings();
        if (shardMappings != null) {
            var indexDir = backupDir.resolve(INDEX_DIR_NAME);
            return shardMappings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> (Partition) new SolrShardPartition(collectionName, e.getKey(), indexDir, e.getValue()))
                .toList();
        }

        // Fall back to directory-based shard discovery
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
        log.atInfo().setMessage("Converted Solr schema to OpenSearch mappings: {} fields, {} shards")
            .addArgument(mappings.path("properties").size()).addArgument(partitionCount).log();
        return new CollectionMetadata(collectionName, partitionCount, Map.of(
            CollectionMetadata.ES_MAPPINGS, mappings
        ));
    }

    @Override
    public Flux<Document> readDocuments(Partition partition, long startingDocOffset) {
        var solrPartition = (SolrShardPartition) partition;
        if (solrPartition.fileNameMapping() != null) {
            return readLuceneIndexMapped(solrPartition.indexPath(), solrPartition.fileNameMapping(), startingDocOffset);
        }
        return readLuceneIndex(solrPartition.indexPath(), startingDocOffset);
    }

    /**
     * Parses shard_backup_metadata/ to build per-shard filename mappings.
     * Returns null if no shard metadata exists (not a SolrCloud UUID backup).
     *
     * @return map of shardName → (luceneName → uuid), or null
     */
    private Map<String, Map<String, String>> parseShardMappings() {
        var metadataDir = backupDir.resolve("shard_backup_metadata");
        if (!Files.isDirectory(metadataDir)) {
            return null;
        }
        try {
            // Use SolrBackupLayout to find only the latest metadata file per shard
            var latestFiles = SolrBackupLayout.findLatestShardMetadataFiles(metadataDir);
            if (latestFiles.isEmpty()) return null;

            var result = new LinkedHashMap<String, Map<String, String>>();
            for (var mdFile : latestFiles) {
                // md_shard1_0.json → shard1
                var mdName = mdFile.getFileName().toString();
                var shardName = mdName.replaceFirst("^md_", "").replaceFirst("_\\d+\\.json$", "");

                var tree = MAPPER.readTree(mdFile.toFile());
                var mapping = new LinkedHashMap<String, String>();
                tree.fields().forEachRemaining(entry -> {
                    var fileName = entry.getValue().path("fileName").asText(null);
                    if (fileName != null) {
                        mapping.put(fileName, entry.getKey()); // luceneName → uuid
                    }
                });
                result.put(shardName, mapping);
            }
            log.atInfo().setMessage("Parsed shard mappings for {} shard(s) from {}").addArgument(result.size()).addArgument(metadataDir).log();
            return result;
        } catch (IOException e) {
            throw new SolrBackupReadException("Failed to read shard metadata from " + metadataDir, e);
        }
    }

    /**
     * Discover shard directories from the backup (non-UUID layouts).
     */
    List<Path> discoverShardDirs() {
        if (!Files.isDirectory(backupDir)) {
            log.warn("Backup directory does not exist, treating as empty collection: {}", backupDir);
            return List.of();
        }
        if (hasSegmentsFile(backupDir)) {
            return List.of(backupDir);
        }

        var indexDir = backupDir.resolve(INDEX_DIR_NAME);
        if (hasSegmentsFile(indexDir)) {
            return List.of(indexDir);
        }

        try (var dirs = Files.list(backupDir)) {
            var shardDirs = dirs
                .filter(Files::isDirectory)
                .sorted()
                .flatMap(shardDir -> {
                    if (hasSegmentsFile(shardDir)) {
                        return Stream.of(shardDir);
                    }
                    // Solr 6 SolrCloud BACKUP writes snapshot.shardN/ dirs; stubs may be empty
                    // before the shardPreparer downloads the actual index files.
                    if (shardDir.getFileName().toString().startsWith("snapshot.")) {
                        return Stream.of(shardDir);
                    }
                    var indexPath = shardDir.resolve("data").resolve(INDEX_DIR_NAME);
                    if (hasSegmentsFile(indexPath)) {
                        return Stream.of(indexPath);
                    }
                    return Stream.empty();
                })
                .toList();

            if (shardDirs.isEmpty()) {
                throw new SolrBackupReadException(
                    "No Lucene segments found in backup directory: " + backupDir
                        + ". Expected segments_N files in the directory or its subdirectories.");
            }
            log.atInfo().setMessage("Discovered {} shard(s) in backup: {}").addArgument(shardDirs.size()).addArgument(backupDir).log();
            return shardDirs;
        } catch (IOException e) {
            throw new SolrBackupReadException("Failed to list backup directory: " + backupDir, e);
        }
    }

    private static boolean hasSegmentsFile(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().startsWith(SEGMENTS_FILE_PREFIX));
        } catch (IOException e) {
            throw new SolrBackupReadException("Failed to list directory: " + dir, e);
        }
    }

    /**
     * Read a Lucene index using a MappedDirectory (for SolrCloud backups with shard metadata).
     * Identity mappings (key == value) are safe on any Solr version and fall through to the
     * standard reader path. Actual UUID remappings require Solr 8.9+ (SIP-12) and use
     * IndexReader9's MappedDirectory support.
     */
    private Flux<Document> readLuceneIndexMapped(Path indexDir, Map<String, String> fileNameMapping, long startingDocOffset) {
        boolean hasUuidRemapping = fileNameMapping.entrySet().stream()
            .anyMatch(e -> !e.getKey().equals(e.getValue()));
        if (!hasUuidRemapping) {
            return readLuceneIndex(indexDir, startingDocOffset);
        }
        if (solrMajorVersion < 8) {
            return Flux.error(new IllegalStateException(
                "SolrCloud UUID-mapped (incremental) backups are not supported for Solr "
                    + solrMajorVersion + ".x; SIP-12 was introduced in Solr 8.9. Use a non-incremental backup."));
        }
        try {
            var fsDir = FSDirectory.open(indexDir);
            var mappedDir = new MappedDirectory(fsDir, fileNameMapping);

            var segmentsFile = fileNameMapping.keySet().stream()
                .filter(name -> name.startsWith(SEGMENTS_FILE_PREFIX))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No segments_N in shard mapping for " + indexDir));

            var reader = new IndexReader9(indexDir, false, null);
            var directoryReader = reader.getReader(mappedDir, segmentsFile);

            log.atInfo().setMessage("Reading Solr backup (mapped): {} docs in {} segments from {}")
                .addArgument(directoryReader.maxDoc()).addArgument(directoryReader.leaves().size()).addArgument(indexDir).log();

            return readFromDirectoryReader(directoryReader, startingDocOffset);
        } catch (IOException e) {
            return Flux.error(new RuntimeException("Failed to open Solr backup: " + indexDir, e));
        }
    }

    /**
     * Read a Lucene index directly from filesystem (for standalone backups).
     */
    private Flux<Document> readLuceneIndex(Path indexDir, long startingDocOffset) {
        try {
            var reader = newLuceneReader(indexDir);
            var segmentsFile = findSegmentsFile(indexDir);
            var directoryReader = reader.getReader(segmentsFile);

            log.atInfo().setMessage("Reading Solr backup: {} docs in {} segments from {}")
                .addArgument(directoryReader.maxDoc()).addArgument(directoryReader.leaves().size()).addArgument(indexDir).log();

            return readFromDirectoryReader(directoryReader, startingDocOffset);
        } catch (IOException e) {
            return Flux.error(new RuntimeException("Failed to open Solr backup: " + indexDir, e));
        }
    }

    private Flux<Document> readFromDirectoryReader(
        org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader directoryReader,
        long startingDocOffset
    ) {
        var scheduler = Schedulers.newBoundedElastic(10, Integer.MAX_VALUE, "solrReader");

        return LuceneReader.getSegmentsFromStartingSegment(
                directoryReader.leaves(), (int) startingDocOffset)
            .concatMap(readerAndBase -> LuceneReader.<LuceneDocumentChange>readLiveDocsFromSegment(
                readerAndBase, (int) startingDocOffset, 10, scheduler,
                (reader, docIdx, segDocBase) -> Mono.justOrEmpty(
                    SolrLuceneDocReader.getDocument(
                        reader, docIdx, true, segDocBase, DocumentChangeType.INDEX, mappingContext))))
            .map(SolrBackupSource::toDocument)
            .doFinally(s -> scheduler.dispose());
    }

    private static String findSegmentsFile(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                .map(p -> p.getFileName().toString())
                .filter(name -> name.startsWith(SEGMENTS_FILE_PREFIX))
                .findFirst()
                .orElseThrow(() -> new SolrBackupReadException("No segments_N file found in: " + dir));
        } catch (IOException e) {
            throw new SolrBackupReadException("Failed to list directory: " + dir, e);
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
