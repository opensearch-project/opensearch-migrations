package org.opensearch.migrations.bulkload;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EncryptedShardBucketCollectionDocumentRepository extends LuceneBasedDocumentRepository {

    private final static ObjectMapper OBJECT_MAPPER ;
    static {
        OBJECT_MAPPER = new ObjectMapper();
    }

    Map<String, List<String>> index2ShardBucketKeyRoots;
    S3DecryptingDownloadManager downloadManager;

    public EncryptedShardBucketCollectionDocumentRepository(String configStr)
        throws JsonProcessingException
    {
        var configMap = OBJECT_MAPPER.readValue(configStr, Map.class);
        this.index2ShardBucketKeyRoots = OBJECT_MAPPER.convertValue(configMap.get("indices"), new TypeReference<>() {});
        downloadManager = new S3DecryptingDownloadManager(
            (String) configMap.get("bucket"),
            (String) configMap.get("region"),
            (String) configMap.get("kmsDecryptRole"),
            (String) configMap.get("localDownloadDirectory"));
    }

    @Override
    public Stream<String> getIndexNamesInSnapshot() {
        return index2ShardBucketKeyRoots.keySet().stream();
    }

    @Override
    public int getNumShards(String indexName) {
        return index2ShardBucketKeyRoots.get(indexName).size();
    }

    @Override
    public LuceneIndexReader getReader(String index, int shard) {
        return new IndexReader9(downloadManager.downloadToLocalPath(index, shard), false, null);
    }

    @Override
    public Duration expectedMaxShardSetupTime() {
        return Duration.ofSeconds(600);
    }

    @Override
    public long getShardSizeInBytes(String name, Integer shard) {
        return downloadManager.getShardSizeInBytes(name, shard);
    }

    public static class S3DecryptingDownloadManager {
        protected final String bucket;
        protected final String region;
        protected final String kmsDecryptRole;
        protected final String localPath;

        public S3DecryptingDownloadManager(String bucket, String region, String kmsDecryptRole, String localPath) {
            this.bucket = bucket;
            this.region = region;
            this.kmsDecryptRole = kmsDecryptRole;
            this.localPath = localPath;
        }

        public long getShardSizeInBytes(String name, Integer shard) {
            log.atError().setMessage("RETURNING BOGUS VALUE FOR SHARD SIZE!").log();
            return 1024;
        }

        public Path downloadToLocalPath(String index, int shard) {
            return null;
        }
    }
}
