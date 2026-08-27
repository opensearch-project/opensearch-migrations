package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Closes a session to further writes by publishing a manifest of what it holds. Only a sealed
 * session can be read as a document source.
 *
 * <p>Workers do one partition each and exit, so several may seal at once. The write is conditional:
 * the first wins and the others compare digests. Disagreement means the session was still being
 * written, so it fails rather than picking a winner.
 *
 * <p>A seal is permanent. Correcting one means copying the objects into a new session.
 */
@Slf4j
public class SessionSealer {

    private final FailedDocumentStreamObjectStore store;

    public SessionSealer(FailedDocumentStreamObjectStore store) {
        this.store = store;
    }

    public record SealResult(SessionManifest manifest, String digest, boolean publishedByThisCaller) {}

    /**
     * Seal, or confirm an existing seal with the same contents.
     *
     * @throws SessionSealMismatchException an existing manifest disagrees with what is there now
     */
    public SealResult seal(String prefix, String sessionId) throws IOException {
        var manifest = buildFromListing(prefix, sessionId);
        var bytes = SessionManifestCodec.toCanonicalBytes(manifest);
        var digest = SessionManifestCodec.digest(bytes);
        var key = FailedDocumentStreamLayout.manifestKey(prefix, sessionId);

        if (store.putIfAbsent(key, bytes)) {
            log.atInfo().setMessage("Sealed failure-stream session '{}' with {} collection(s), digest {}")
                .addArgument(sessionId)
                .addArgument(manifest.collections().size())
                .addArgument(digest)
                .log();
            return new SealResult(manifest, digest, true);
        }

        var existingBytes = store.read(key).orElseThrow(() -> new IOException(
            "The manifest at " + key + " could not be written and could not be read back either."));
        var existingDigest = SessionManifestCodec.digest(existingBytes);
        if (!existingDigest.equals(digest)) {
            throw new SessionSealMismatchException(sessionId, key, existingDigest, digest);
        }
        log.atInfo().setMessage("Failure-stream session '{}' was already sealed with the same contents ({})")
            .addArgument(sessionId).addArgument(digest).log();
        return new SealResult(SessionManifestCodec.parse(existingBytes), existingDigest, false);
    }

    /** The manifest a live listing implies right now. */
    public SessionManifest buildFromListing(String prefix, String sessionId) throws IOException {
        var sessionPrefix = FailedDocumentStreamLayout.sessionPrefix(prefix, sessionId);
        // Grouping only; SessionManifest sorts on construction.
        Map<String, Map<String, List<String>>> byCollection = new LinkedHashMap<>();
        for (var key : store.listKeys(sessionPrefix)) {
            var location = FailedDocumentStreamLayout.locationOf(key);
            if (location.isEmpty()) {
                // The manifest itself, or anything else under the prefix.
                log.atDebug().setMessage("Ignoring non-record object while sealing: {}").addArgument(key).log();
                continue;
            }
            byCollection
                .computeIfAbsent(location.get().collectionName(), c -> new LinkedHashMap<>())
                .computeIfAbsent(location.get().partitionName(), p -> new ArrayList<>())
                .add(key);
        }
        var collections = new ArrayList<SessionManifest.CollectionEntry>(byCollection.size());
        byCollection.forEach((collectionName, partitions) -> {
            var entries = new ArrayList<SessionManifest.PartitionEntry>(partitions.size());
            partitions.forEach((partitionName, keys) ->
                entries.add(new SessionManifest.PartitionEntry(partitionName, keys)));
            collections.add(new SessionManifest.CollectionEntry(collectionName, entries));
        });
        return new SessionManifest(SessionManifest.CURRENT_SCHEMA_VERSION, sessionId, collections);
    }

    /** Null when the session has never been sealed. */
    public static SessionManifest readManifest(
        FailedDocumentStreamObjectStore store, String prefix, String sessionId
    ) throws IOException {
        var key = FailedDocumentStreamLayout.manifestKey(prefix, sessionId);
        var bytes = store.read(key);
        if (bytes.isEmpty()) {
            return null;
        }
        return SessionManifestCodec.parse(bytes.get());
    }

    /**
     * Check a sealed manifest against the store. A seal promises the session was closed, so drift
     * means it was not, and the reader must not work from a stale inventory.
     */
    public static void verifyAgainstListing(
        FailedDocumentStreamObjectStore store, String prefix, SessionManifest manifest
    ) throws IOException {
        var sessionPrefix = FailedDocumentStreamLayout.sessionPrefix(prefix, manifest.sessionId());
        var live = store.listKeys(sessionPrefix).stream()
            .filter(FailedDocumentStreamLayout::isRecordObject)
            .sorted()
            .toList();
        var sealed = manifest.allObjectKeys();
        if (live.equals(sealed)) {
            return;
        }
        var missing = new ArrayList<>(sealed);
        missing.removeAll(live);
        var extra = new ArrayList<>(live);
        extra.removeAll(sealed);
        throw new IOException("Failure-stream session '" + manifest.sessionId()
            + "' does not match its seal: " + missing.size() + " object(s) named by the manifest are gone"
            + (missing.isEmpty() ? "" : " (e.g. " + missing.get(0) + ")")
            + " and " + extra.size() + " object(s) appeared after it was sealed"
            + (extra.isEmpty() ? "" : " (e.g. " + extra.get(0) + ")")
            + ". A seal is permanent; copy the objects into a new session and seal that instead.");
    }

    /** Two sealers disagreed, so the session was still being written. */
    public static class SessionSealMismatchException extends IOException {
        public SessionSealMismatchException(String sessionId, String key, String existing, String attempted) {
            super("Failure-stream session '" + sessionId + "' is already sealed at " + key
                + " with digest " + existing + ", but sealing it now would produce " + attempted
                + ". The session was still being written when it was first sealed. A seal is permanent;"
                + " copy the objects into a new session and seal that instead.");
        }
    }
}
