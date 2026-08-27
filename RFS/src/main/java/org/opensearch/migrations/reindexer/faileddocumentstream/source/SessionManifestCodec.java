package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

/**
 * Canonical bytes for a {@link SessionManifest}, and back again.
 *
 * <p>A losing sealer proves it lost by comparing digests, so the encoding must be reproducible:
 * fixed property order over already-sorted content, nothing from the clock or map iteration.
 *
 * <p>{@code SessionManifestCrossLanguageTest} pins the result against the console's sealer.
 */
@UtilityClass
public class SessionManifestCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String FIELD_SCHEMA_VERSION = "schemaVersion";
    static final String FIELD_SESSION_ID = "sessionId";
    static final String FIELD_COLLECTIONS = "collections";
    static final String FIELD_NAME = "name";
    static final String FIELD_PARTITIONS = "partitions";
    static final String FIELD_OBJECT_KEYS = "objectKeys";

    public static byte[] toCanonicalBytes(SessionManifest manifest) {
        var root = JsonNodeFactory.instance.objectNode();
        root.put(FIELD_SCHEMA_VERSION, manifest.schemaVersion());
        root.put(FIELD_SESSION_ID, manifest.sessionId());
        var collections = root.putArray(FIELD_COLLECTIONS);
        // Already sorted by the record's constructor; this only fixes property order.
        for (var collection : manifest.collections()) {
            ObjectNode collectionNode = collections.addObject();
            collectionNode.put(FIELD_NAME, collection.name());
            var partitions = collectionNode.putArray(FIELD_PARTITIONS);
            for (var partition : collection.partitions()) {
                ObjectNode partitionNode = partitions.addObject();
                partitionNode.put(FIELD_NAME, partition.name());
                var keys = partitionNode.putArray(FIELD_OBJECT_KEYS);
                partition.objectKeys().forEach(keys::add);
            }
        }
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize the session manifest", e);
        }
    }

    public static SessionManifest parse(byte[] bytes) throws IOException {
        JsonNode root = MAPPER.readTree(bytes);
        var schemaVersion = root.path(FIELD_SCHEMA_VERSION).asInt(-1);
        if (schemaVersion != SessionManifest.CURRENT_SCHEMA_VERSION) {
            throw new IOException("Unsupported failure-stream manifest schema version " + schemaVersion
                + "; this build reads version " + SessionManifest.CURRENT_SCHEMA_VERSION
                + ". Upgrade the tooling that reads this session.");
        }
        var sessionId = root.path(FIELD_SESSION_ID).asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IOException("Failure-stream manifest is missing '" + FIELD_SESSION_ID + "'");
        }
        List<SessionManifest.CollectionEntry> collections = new ArrayList<>();
        for (var collectionNode : root.path(FIELD_COLLECTIONS)) {
            List<SessionManifest.PartitionEntry> partitions = new ArrayList<>();
            for (var partitionNode : collectionNode.path(FIELD_PARTITIONS)) {
                List<String> keys = new ArrayList<>();
                partitionNode.path(FIELD_OBJECT_KEYS).forEach(key -> keys.add(key.asText()));
                partitions.add(new SessionManifest.PartitionEntry(
                    partitionNode.path(FIELD_NAME).asText(), keys));
            }
            collections.add(new SessionManifest.CollectionEntry(
                collectionNode.path(FIELD_NAME).asText(), partitions));
        }
        return new SessionManifest(schemaVersion, sessionId, collections);
    }

    /** Hex SHA-256 over the canonical bytes. */
    public static String digest(byte[] canonicalBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException e) {
            // Required of every JRE.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** For callers holding a manifest rather than its bytes. */
    public static String digest(SessionManifest manifest) {
        return digest(toCanonicalBytes(manifest));
    }

    static String toCanonicalString(SessionManifest manifest) {
        return new String(toCanonicalBytes(manifest), StandardCharsets.UTF_8);
    }
}
