# RFS Library and Metadata Migration Investigation

## Overview

This document details the code paths and architecture for extracting metadata from Elasticsearch/OpenSearch snapshots stored in S3, retrieving index field mappings, and migrating documents using the RFS (Reindex From Snapshot) library.

---

## 1. Snapshot Repository Structure

### S3 Snapshot Layout
```
s3://bucket/snapshot-repo/
├── index-N                          # Repository index file (JSON)
├── meta-<snapshot_uuid>.dat         # Global metadata (SMILE format)
├── snap-<snapshot_uuid>.dat         # Snapshot metadata (SMILE format)
└── indices/
    └── <index_uuid>/
        ├── meta-<metadata_id>.dat   # Index metadata (SMILE format)
        └── <shard_id>/
            ├── snap-<snapshot_uuid>.dat  # Shard metadata
            └── __<segment_files>         # Lucene segment blobs
```

### File Formats
- **index-N**: Plain JSON containing snapshot and index listings
- **\*.dat files**: SMILE format (binary JSON) with optional DEFLATE compression
- **Compression detection**: `SnapshotMetadataLoader` checks for `DFL\0` header

---

## 2. Core Architecture

### Class Hierarchy

```
SourceRepo (interface)
├── S3Repo              # Downloads from S3 to local cache
└── FileSystemRepo      # Reads from local filesystem

ClusterSnapshotReader (interface)
├── SnapshotReader_ES_1_7
├── SnapshotReader_ES_2_4
├── SnapshotReader_ES_6_8
└── SnapshotReader_ES_7_10

SnapshotRepo.Provider (interface)
├── SnapshotRepoProvider_ES_1_7
├── SnapshotRepoProvider_ES_2_4
├── SnapshotRepoProvider_ES_6_8
└── SnapshotRepoProvider_ES_7_10
```

### Version Selection
```java
// ClusterProviderRegistry.java
private List<VersionSpecificCluster> getProviders() {
    return List.of(
        new SnapshotReader_ES_1_7(),
        new SnapshotReader_ES_2_4(),
        new SnapshotReader_ES_6_8(),
        new SnapshotReader_ES_7_10(),
        new RemoteWriter_OS_2_11(),
        new RemoteWriter_ES_6_8(),
        new RemoteReader()
    );
}
```

---

## 3. Metadata Extraction from S3

### Code Path
```
S3Repo.create(s3LocalDir, s3Uri, s3Region, finder)
    │
    ├── getSnapshotRepoDataFilePath()
    │   ├── listFilesInS3Root()           # Lists top-level files
    │   └── fileFinder.getSnapshotRepoDataFilePath()  # Finds index-N
    │
    └── fetch(path)
        └── ensureFileExistsLocally()     # Downloads if not cached
            └── s3Client.getObject()
```

### Key Files

**S3Repo.java** (`RFS/src/main/java/org/opensearch/migrations/bulkload/common/S3Repo.java`)
```java
public class S3Repo implements SourceRepo {
    // Downloads files on-demand to local cache
    public Path getSnapshotRepoDataFilePath() {
        List<String> filesInRoot = listFilesInS3Root();
        Path repoDataPath = fileFinder.getSnapshotRepoDataFilePath(s3LocalDir, filesInRoot);
        return fetch(repoDataPath);  // Downloads if needed
    }
    
    public Path getIndexMetadataFilePath(String indexId, String indexFileId) {
        return fetch(fileFinder.getIndexMetadataFilePath(s3LocalDir, indexId, indexFileId));
    }
}
```

**SnapshotRepoData_ES_7_10.java** (`RFS/src/main/java/.../version_es_7_10/SnapshotRepoData_ES_7_10.java`)
```java
// Parsed structure of index-N file
public class SnapshotRepoData_ES_7_10 {
    private List<Snapshot> snapshots;                    // All snapshots
    private Map<String, RawIndex> indices;               // Index name → metadata
    private Map<String, String> indexMetadataIdentifiers; // Lookup key → file ID
    
    public static class Snapshot {
        private String name;
        private String uuid;
        private Map<String, String> indexMetadataLookup;  // Index ID → metadata key
    }
}
```

---

## 4. Index Metadata and Field Mappings Retrieval

### Code Path
```
IndexMetadata.Factory.fromRepo(snapshotName, indexName)
    │
    ├── getIndexFileId(snapshotName, indexName)
    │   └── SnapshotRepoProvider.getIndexMetadataId()
    │       ├── getIndexId(indexName)                    # From indices map
    │       └── snapshot.indexMetadataLookup.get(indexId) # Get metadata key
    │           └── indexMetadataIdentifiers.get(key)    # Get actual file ID
    │
    ├── getJsonNode(indexId, indexFileId, smileFactory)
    │   ├── repo.getIndexMetadataFilePath(indexId, indexFileId)
    │   ├── SnapshotMetadataLoader.processMetadataBytes()  # Decompress if needed
    │   └── smileMapper.readTree()                         # Parse SMILE
    │
    └── fromJsonNode(root, indexId, indexName)
        └── new IndexMetadataData_ES_7_10(root, indexId, indexName)
```

### Key Files

**IndexMetadataFactory_ES_7_10.java**
```java
public class IndexMetadataFactory_ES_7_10 implements IndexMetadata.Factory {
    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        ObjectNode objectNodeRoot = (ObjectNode) root.get(indexName);
        return new IndexMetadataData_ES_7_10(objectNodeRoot, indexId, indexName);
    }
    
    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        SnapshotRepoProvider_ES_7_10 provider = (SnapshotRepoProvider_ES_7_10) repoDataProvider;
        return provider.getIndexMetadataId(snapshotName, indexName);
    }
}
```

**IndexMetadataData_ES_7_10.java**
```java
public class IndexMetadataData_ES_7_10 implements IndexMetadata {
    private final ObjectNode rawJson;
    
    @Override
    public ObjectNode getMappings() {
        return (ObjectNode) rawJson.get("mappings");
    }
    
    @Override
    public ObjectNode getSettings() {
        // Converts flat settings to tree structure
        return TransformFunctions.convertFlatSettingsToTree((ObjectNode) rawJson.get("settings"));
    }
    
    @Override
    public ObjectNode getAliases() {
        return (ObjectNode) rawJson.get("aliases");
    }
    
    @Override
    public int getNumberOfShards() {
        return getSettings().get("index").get("number_of_shards").asInt();
    }
}
```

### Mappings JSON Structure
```json
{
  "mappings": {
    "properties": {
      "title": { "type": "text", "analyzer": "standard" },
      "timestamp": { "type": "date" },
      "count": { "type": "integer" },
      "location": { "type": "geo_point" },
      "tags": { "type": "keyword" },
      "nested_field": {
        "type": "nested",
        "properties": {
          "inner": { "type": "text" }
        }
      }
    }
  }
}
```

---

## 5. Global Metadata Extraction

### Code Path
```
GlobalMetadata.Factory.fromRepo(snapshotName)
    │
    ├── repoDataProvider.getSnapshotId(snapshotName)
    │
    ├── repo.getGlobalMetadataFilePath(snapshotId)
    │   └── Returns path to meta-<uuid>.dat
    │
    ├── SnapshotMetadataLoader.processMetadataBytes()
    │
    └── fromJsonNode(root)
        └── new GlobalMetadataData_ES_7_10(root.get("meta-data"))
```

### Key Files

**GlobalMetadataData_ES_7_10.java**
```java
public class GlobalMetadataData_ES_7_10 implements GlobalMetadata {
    private final ObjectNode root;
    
    @Override
    public JsonPointer getTemplatesPath() {
        return JsonPointer.compile("/templates");  // Legacy templates
    }
    
    @Override
    public JsonPointer getIndexTemplatesPath() {
        return JsonPointer.compile("/index_template/index_template");
    }
    
    @Override
    public JsonPointer getComponentTemplatesPath() {
        return JsonPointer.compile("/component_template/component_template");
    }
    
    // Inherited default methods use these paths:
    // getTemplates(), getIndexTemplates(), getComponentTemplates()
}
```

### Global Metadata Structure
```json
{
  "meta-data": {
    "templates": {
      "legacy_template_name": { "index_patterns": [...], "mappings": {...} }
    },
    "index_template": {
      "index_template": {
        "template_name": { "index_patterns": [...], "template": {...} }
      }
    },
    "component_template": {
      "component_template": {
        "component_name": { "template": {...} }
      }
    }
  }
}
```

---

## 6. Document Migration Architecture

### Entry Point Flow
```
RfsMigrateDocuments.main(args)
    │
    ├── Parse arguments (snapshot location, target cluster, etc.)
    │
    ├── Create OpenSearchClient for target
    │
    ├── Create SourceRepo (S3Repo or FileSystemRepo)
    │
    ├── Get ClusterSnapshotReader via ClusterProviderRegistry
    │
    └── run()
        │
        ├── confirmShardPrepIsComplete()
        │   └── ShardWorkPreparer.run()
        │       └── Creates work items for each index/shard
        │
        └── DocumentsRunner.migrateNextShard()
            └── Loop until no work left
```

### ShardWorkPreparer
```java
// ShardWorkPreparer.java
private static void prepareShardWorkItems(...) {
    for (SnapshotRepo.Index index : repoDataProvider.getIndicesInSnapshot(snapshotName)) {
        if (!allowedIndexes.test(index.getName())) continue;
        
        IndexMetadata metadata = metadataFactory.fromRepo(snapshotName, index.getName());
        
        for (int shardId = 0; shardId < metadata.getNumberOfShards(); shardId++) {
            workCoordinator.createUnassignedWorkItem(
                new WorkItem(metadata.getName(), shardId, Integer.MIN_VALUE).toString()
            );
        }
    }
}
```

### DocumentsRunner Flow
```
DocumentsRunner.migrateNextShard()
    │
    ├── workCoordinator.acquireNextWorkItem()  # Get lease on work item
    │
    └── setupDocMigration(workItem)
        │
        ├── documentReaderEngine.createUnpacker()
        │   └── SnapshotShardUnpacker.create()
        │
        ├── unpacker.unpack()
        │   └── Extracts Lucene files from snapshot blobs
        │
        ├── readerFactory.getReader(unpackedPath)
        │   └── Opens Lucene DirectoryReader
        │
        ├── documentReaderEngine.prepareChangeset()
        │   └── LuceneReader.readDocsByLeavesFromStartingPosition()
        │
        └── reindexer.reindex(indexName, documents)
            ├── transformDocumentBatch()
            └── sendBulkRequest()
```

### SnapshotShardUnpacker
```java
// SnapshotShardUnpacker.java
public Path unpack() {
    Files.createDirectories(targetDirectory);
    
    try (FSDirectory primaryDirectory = FSDirectory.open(targetDirectory)) {
        Flux.fromIterable(filesToUnpack)
            .flatMap(fileMetadata -> unpackFile(primaryDirectory, fileMetadata))
            .blockLast();
    }
    return targetDirectory;
}

private Mono<Void> unpackFile(FSDirectory dir, ShardFileInfo fileMetadata) {
    // Virtual files (v__*) contain hash in metadata
    if (fileMetadata.getName().startsWith("v__")) {
        indexOutput.writeBytes(fileMetadata.getMetaHash().bytes, ...);
    } else {
        // Regular files read from blob parts
        try (var stream = new PartSliceStream(repoAccessor, fileMetadata, indexId, shardId)) {
            indexOutput.copyBytes(new InputStreamDataInput(stream), fileMetadata.getLength());
        }
    }
}
```

### LuceneReader Document Extraction
```java
// LuceneReader.java
public static LuceneDocumentChange getDocument(LuceneLeafReader reader, int luceneDocId, ...) {
    LuceneDocument document = reader.document(luceneDocId);
    
    String openSearchDocId = null;
    String type = null;
    String sourceBytes = null;
    String routing = null;
    
    for (var field : document.getFields()) {
        switch (field.name()) {
            case "_id":      openSearchDocId = field.asUid(); break;
            case "_uid":     // ES <= 5: "type#id" format
                var parts = field.stringValue().split("#", 2);
                type = parts[0];
                openSearchDocId = parts[1];
                break;
            case "_source":  sourceBytes = field.utf8ToStringValue(); break;
            case "_routing": routing = field.stringValue(); break;
        }
    }
    
    // Reconstruct _source if missing
    if (sourceBytes == null) {
        sourceBytes = SourceReconstructor.reconstructSource(reader, luceneDocId, document);
    }
    
    return new LuceneDocumentChange(docId, openSearchDocId, type, sourceBytes, routing, operation);
}
```

---

## 7. Metadata Transformation

### Transformer Chain
```
MigratorEvaluatorBase.selectTransformer()
    │
    ├── TransformerMapper.getTransformer()  # Version-specific transforms
    │   └── Transformer_ES_7_10_OS_2_11
    │
    └── getCustomTransformer()              # User-supplied transforms
        └── MetadataTransformationRegistry.configToTransformer()
```

### Key Transformations

**TransformFunctions.java**
```java
// Convert flat settings to tree
// "index.number_of_replicas": "1" → { "index": { "number_of_replicas": "1" } }
public static ObjectNode convertFlatSettingsToTree(ObjectNode flatSettings)

// Remove intermediate mappings level
// [{"_doc": {"properties": {...}}}] → {"properties": {...}}
public static void removeIntermediateMappingsLevels(ObjectNode root)

// Fix replica count for zone awareness
public static void fixReplicasForDimensionality(ObjectNode root, int dimensionality)
```

**IndexMappingTypeRemoval.java**
```java
// Handles multi-type mappings (ES 5.x/6.x → ES 7.x+)
public enum MultiTypeResolutionBehavior {
    NONE,   // Throw error on multi-type
    UNION,  // Merge all types into single mapping
    SPLIT   // Create separate indices per type
}
```

---

## 8. Caveats and Edge Cases

### 8.1 SMILE Format and Compression
```java
// SnapshotMetadataLoader.java
public static InputStream processMetadataBytes(byte[] bytes, String codecName) {
    if (findDFLHeader(bytes) != -1) {
        // OpenSearch DEFLATE compression
        return new ByteArrayInputStream(decompress(bytes));
    } else {
        // Standard Lucene codec format
        CodecUtil.checksumEntireFile(indexInput);
        CodecUtil.checkHeader(indexInput, codecName, 1, 1);
        return new ByteArrayInputStream(bytes, filePointer, bytes.length - filePointer);
    }
}
```

### 8.2 Excluded Indices
```java
// FilterScheme.java
private static final List<String> EXCLUDED_PREFIXES = Arrays.asList(
    ".", "apm-", "apm@", "behavioral_analytics-", "data-streams-",
    "elastic-connectors-", "ilm-history-", "logs-", "metrics-", "profiling-", ...
);

private static final List<String> EXCLUDED_NAMES = Arrays.asList(
    "agentless", "elastic-connectors", "ilm-history", "logs", "metrics",
    "profiling", "synthetics", "watches", ...
);
```

### 8.3 Source Reconstruction
```java
// SourceReconstructor.java - When _source is disabled
public static String reconstructSource(LuceneLeafReader reader, int docId, LuceneDocument document) {
    Map<String, Object> reconstructed = new LinkedHashMap<>();
    
    // 1. Read from stored fields first (more performant)
    for (var field : document.getFields()) {
        if (!shouldSkipField(field.name())) {
            reconstructed.put(field.name(), getStoredFieldValue(field));
        }
    }
    
    // 2. Fill gaps from doc_values
    for (DocValueFieldInfo fieldInfo : reader.getDocValueFields()) {
        if (!reconstructed.containsKey(fieldInfo.name())) {
            reconstructed.put(fieldInfo.name(), reader.getDocValue(docId, fieldInfo));
        }
    }
    
    return OBJECT_MAPPER.writeValueAsString(reconstructed);
}
```

### 8.4 Awareness Attribute Handling
```java
// InvalidResponse.java
// Detects when replica count doesn't match zone count
private static final Pattern AWARENESS_ATTRIBUTE_EXCEPTION = 
    Pattern.compile("expected total copies needs to be a multiple of total awareness attributes");

// IndexCreator_OS_2_11.java
// Automatically adjusts replica count or removes problematic settings
```

### 8.5 Work Coordination and Lease Management
```java
// DocumentsRunner handles lease expiration gracefully
// Creates successor work items to resume from last checkpoint

private static List<String> getSuccessorWorkItemIds(WorkItemAndDuration workItem, WorkItemCursor cursor) {
    var successorWorkItem = new WorkItem(
        workItem.getIndexName(),
        workItem.getShardNumber(),
        cursor.getProgressCheckpointNum()  // Resume from last processed doc
    );
    return List.of(successorWorkItem.toString());
}
```

### 8.6 Version-Specific Differences

| Version | Snapshot Format | Mappings | Notes |
|---------|----------------|----------|-------|
| ES 1.7 | Legacy | Multi-type | `_uid` field contains type#id |
| ES 2.4 | Legacy | Multi-type | Similar to 1.7 |
| ES 6.8 | Modern | Single-type default | Transition version |
| ES 7.10+ | Modern | Single-type only | `_id` field only |
| OS 2.x | Modern | Single-type only | Compatible with ES 7.x |

---

## 9. Usage Examples

### Retrieving Index Mappings Programmatically
```java
// 1. Create source repo
SourceRepo repo = S3Repo.create(
    Paths.get("/tmp/s3-cache"),
    new S3Uri("s3://my-bucket/snapshots"),
    "us-east-1",
    ClusterProviderRegistry.getSnapshotFileFinder(version, true)
);

// 2. Get snapshot reader
ClusterSnapshotReader reader = ClusterProviderRegistry.getSnapshotReader(
    Version.fromString("ES 7.10"),
    repo,
    true  // loose version matching
);

// 3. Get index metadata factory
IndexMetadata.Factory metadataFactory = reader.getIndexMetadata();

// 4. Retrieve specific index metadata
IndexMetadata indexMetadata = metadataFactory.fromRepo("my-snapshot", "my-index");

// 5. Access mappings
ObjectNode mappings = indexMetadata.getMappings();
JsonNode properties = mappings.get("properties");

// Iterate fields
properties.fieldNames().forEachRemaining(fieldName -> {
    JsonNode fieldDef = properties.get(fieldName);
    String type = fieldDef.get("type").asText();
    System.out.println(fieldName + ": " + type);
});
```

### Listing All Indices in Snapshot
```java
SnapshotRepo.Provider repoProvider = new SnapshotRepoProvider_ES_7_10(repo);

// Get all indices in a specific snapshot
List<SnapshotRepo.Index> indices = repoProvider.getIndicesInSnapshot("my-snapshot");

for (SnapshotRepo.Index index : indices) {
    System.out.println("Index: " + index.getName() + " (ID: " + index.getId() + ")");
}
```

---

## 10. Key File Locations

| Component | Path |
|-----------|------|
| S3 Repository | `RFS/src/main/java/org/opensearch/migrations/bulkload/common/S3Repo.java` |
| Snapshot Repo Provider | `RFS/src/main/java/org/opensearch/migrations/bulkload/version_es_7_10/SnapshotRepoProvider_ES_7_10.java` |
| Index Metadata | `RFS/src/main/java/org/opensearch/migrations/bulkload/version_es_7_10/IndexMetadataData_ES_7_10.java` |
| Global Metadata | `RFS/src/main/java/org/opensearch/migrations/bulkload/version_es_7_10/GlobalMetadataData_ES_7_10.java` |
| Document Runner | `RFS/src/main/java/org/opensearch/migrations/bulkload/worker/DocumentsRunner.java` |
| Lucene Reader | `RFS/src/main/java/org/opensearch/migrations/bulkload/lucene/LuceneReader.java` |
| Shard Unpacker | `RFS/src/main/java/org/opensearch/migrations/bulkload/common/SnapshotShardUnpacker.java` |
| Metadata Migration Entry | `MetadataMigration/src/main/java/org/opensearch/migrations/MetadataMigration.java` |
| Document Migration Entry | `DocumentsFromSnapshotMigration/src/main/java/org/opensearch/migrations/RfsMigrateDocuments.java` |
| Transform Functions | `RFS/src/main/java/org/opensearch/migrations/bulkload/transformers/TransformFunctions.java` |
| Filter Scheme | `RFS/src/main/java/org/opensearch/migrations/bulkload/common/FilterScheme.java` |
| Source Reconstructor | `RFS/src/main/java/org/opensearch/migrations/bulkload/lucene/SourceReconstructor.java` |
