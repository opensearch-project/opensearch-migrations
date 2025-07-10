package org.opensearch.migrations.bulkload.version_es_2_4;

import java.io.IOException;
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

    @Override
    public IndexMetadata fromRepo(String snapshotName, String indexName) {
        try {
            byte[] rawBytes = repoProvider.getIndexMetadataFile(indexName);

            if (isSmile(rawBytes)) {
                log.warn("Index metadata file for [{}] appears to be Smile/JSON, not ES 2.4 binary! Routing to ES 6.8 reader.", indexName);
                return readWithES68Factory(rawBytes, snapshotName, indexName);
            } else {
                // log.info("Detected ES 2.4 binary format for index metadata of [{}]", indexName);
                // ByteArrayStreamInput_ES_2_4 in = new ByteArrayStreamInput_ES_2_4(rawBytes);
                // return GlobalMetadataFactory_ES_2_4.readIndexMetadata(in);
                return readWithES68Factory(rawBytes, snapshotName, indexName);
            }

        } catch (Exception e) {
            throw new RuntimeException("Error reading index metadata for: " + indexName, e);
        }
    }

    public List<IndexMetadata> fromSnapshot() {
        List<String> indices = repoProvider.listIndices();
        if (indices == null || indices.isEmpty()) {
            return Collections.emptyList();
        }

        List<IndexMetadata> results = new ArrayList<>();
        for (String indexName : indices) {
            try {
                byte[] rawBytes = repoProvider.getIndexMetadataFile(indexName);
                ByteArrayStreamInput_ES_2_4 in = new ByteArrayStreamInput_ES_2_4(rawBytes);

                IndexMetadata index = GlobalMetadataFactory_ES_2_4.readIndexMetadata(in);
                results.add(index);
            } catch (Exception e) {
                throw new RuntimeException("Error reading index metadata for: " + indexName, e);
            }
        }
        return results;
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        return new IndexMetadataData_ES_2_4(indexName, root);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return null;  // ES 2.4 doesn't use Smile
    }

    @Override
    public String getIndexFileId(String snapshotId, String indexName) {
        return snapshotId;
    }

    private IndexMetadata readWithES68Factory(byte[] rawBytes, String snapshotName, String indexName) throws IOException {
        var es68Factory = new IndexMetadataFactory_ES_6_8(this.repoProvider);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(es68Factory.getSmileFactory());
        JsonNode root = mapper.readTree(rawBytes);
        String indexId = es68Factory.getIndexFileId(snapshotName, indexName);
        return es68Factory.fromJsonNode(root, indexId, indexName);
    }

    private static boolean isSmile(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            return false;
        }
        return (bytes[0] == (byte) 0x3A && bytes[1] == (byte) 0x29)   // standard Smile header
                || bytes[0] == (byte) 0xD7
                || bytes[0] == (byte) 0x3A;
    }
}
