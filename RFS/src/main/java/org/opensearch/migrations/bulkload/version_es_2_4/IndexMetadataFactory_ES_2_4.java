package org.opensearch.migrations.bulkload.version_es_2_4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.opensearch.migrations.bulkload.models.IndexMetadata;
import org.opensearch.migrations.bulkload.version_es_6_8.IndexMetadataFactory_ES_6_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IndexMetadataFactory_ES_2_4 implements IndexMetadata.Factory {

    private final SnapshotRepo_ES_2_4 repoProvider;

    public IndexMetadataFactory_ES_2_4(SnapshotRepo_ES_2_4 repoProvider) {
        this.repoProvider = repoProvider;
    }

    @Override
    public SnapshotRepo_ES_2_4 getRepoDataProvider() {
        return repoProvider;
    }

    /**
     * Reads a single index's metadata from the snapshot.
     * Detects ES 2.4 binary or Smile/JSON format automatically.
     */
    @Override
    public IndexMetadata fromRepo(String snapshotName, String indexName) {
        try {
            byte[] rawBytes = repoProvider.getIndexMetadataFile(indexName);

            if (isSmile(rawBytes)) {
                log.info("Index metadata file for [{}] detected as Smile-encoded JSON. Delegating to ES 6.8 reader.", indexName);
                return readWithES68Factory(snapshotName, indexName);
            } else {
                // log.info("Index metadata file for [{}] detected as ES 2.4 binary format.", indexName);
                // ByteArrayStreamInput_ES_2_4 in = new ByteArrayStreamInput_ES_2_4(rawBytes);
                // return GlobalMetadataFactory_ES_2_4.readIndexMetadata(in);

                // Strictly using ES 6.8 reader for index level metadata file
                log.info("Index metadata file for [{}] detected as Smile-encoded JSON. Delegating to ES 6.8 reader.", indexName);
                return readWithES68Factory(snapshotName, indexName);
            }

        } catch (Exception e) {
            throw new RuntimeException("Error reading index metadata for: " + indexName, e);
        }
    }

    /**
     * Reads *all* index metadata objects in the snapshot.
     * (Note: This assumes ES 2.4 binary; Smile-encoded indices will fail here if not patched similarly.)
     */
    public List<IndexMetadata> fromSnapshot() {
        List<String> indices = repoProvider.listIndices();
        if (indices == null || indices.isEmpty()) {
            return Collections.emptyList();
        }

        List<IndexMetadata> results = new ArrayList<>();
        for (String indexName : indices) {
            results.add(fromRepo("unknown-snapshot", indexName));
        }
        return results;
    }

    /**
     * Called when Smile format is detected. Delegates to ES 6.8's reader.
     */
    private IndexMetadata readWithES68Factory(String snapshotName, String indexName) {
        // Use the delegate repo that understands general (Smile-encoded) formats
        var delegateRepo = this.repoProvider.getDelegateRepo();
        var es68Factory = new IndexMetadataFactory_ES_6_8(delegateRepo);
        return es68Factory.fromRepo(snapshotName, indexName);
    }

    /**
     * Checks whether the byte array starts with a Smile header.
     */
    private static boolean isSmile(byte[] bytes) {
        if (bytes == null || bytes.length < 3) {
            return false;
        }

        // Standard Smile header 0x3A 0x29 0x0A
        if ((bytes[0] == (byte)0x3A && bytes[1] == (byte)0x29 && bytes[2] == (byte)0x0A)
            || bytes[0] == (byte)0xD7) {
            return true;
        }

        return false;
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        return new IndexMetadataData_ES_2_4(indexName, root);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return null; // ES 2.4 native reader does not use Smile
    }

    @Override
    public String getIndexFileId(String snapshotId, String indexName) {
        return snapshotId;
    }
}
