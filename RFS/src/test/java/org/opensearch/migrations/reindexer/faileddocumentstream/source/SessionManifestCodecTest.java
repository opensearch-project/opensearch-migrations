package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionManifestCodecTest {

    private static SessionManifest manifest(List<SessionManifest.CollectionEntry> collections) {
        return new SessionManifest(SessionManifest.CURRENT_SCHEMA_VERSION, "session-1", collections);
    }

    @Test
    void producesTheSameBytesWhateverOrderItWasBuiltIn() {
        // Racing sealers build from independent listings; encoding must not depend on walk order.
        var one = manifest(List.of(
            new SessionManifest.CollectionEntry("users", List.of(
                new SessionManifest.PartitionEntry("worker-b", List.of("k2", "k1")))),
            new SessionManifest.CollectionEntry("orders", List.of(
                new SessionManifest.PartitionEntry("worker-a", List.of("k3")),
                new SessionManifest.PartitionEntry("worker-z", List.of("k4"))))));
        var other = manifest(List.of(
            new SessionManifest.CollectionEntry("orders", List.of(
                new SessionManifest.PartitionEntry("worker-z", List.of("k4")),
                new SessionManifest.PartitionEntry("worker-a", List.of("k3")))),
            new SessionManifest.CollectionEntry("users", List.of(
                new SessionManifest.PartitionEntry("worker-b", List.of("k1", "k2"))))));

        assertThat(SessionManifestCodec.toCanonicalString(other),
            equalTo(SessionManifestCodec.toCanonicalString(one)));
        assertThat(SessionManifestCodec.digest(other), equalTo(SessionManifestCodec.digest(one)));
    }

    @Test
    void differentContentsProduceDifferentDigests() {
        var one = manifest(List.of(new SessionManifest.CollectionEntry("orders",
            List.of(new SessionManifest.PartitionEntry("worker-a", List.of("k1"))))));
        var other = manifest(List.of(new SessionManifest.CollectionEntry("orders",
            List.of(new SessionManifest.PartitionEntry("worker-a", List.of("k1", "k2"))))));

        assertThat(SessionManifestCodec.digest(other),
            org.hamcrest.Matchers.not(equalTo(SessionManifestCodec.digest(one))));
    }

    @Test
    void roundTrips() throws IOException {
        var original = manifest(List.of(
            new SessionManifest.CollectionEntry("orders", List.of(
                new SessionManifest.PartitionEntry("worker-a", List.of("k1", "k2"))))));

        var parsed = SessionManifestCodec.parse(SessionManifestCodec.toCanonicalBytes(original));

        assertThat(parsed, equalTo(original));
    }

    @Test
    void refusesASchemaItCannotRead() {
        var future = "{\"schemaVersion\":99,\"sessionId\":\"s\",\"collections\":[]}".getBytes();

        var thrown = assertThrows(IOException.class, () -> SessionManifestCodec.parse(future));

        assertThat(thrown.getMessage(), containsString("Unsupported failure-stream manifest schema version 99"));
    }

    @Test
    void refusesAManifestWithNoSessionId() {
        var noSession = "{\"schemaVersion\":1,\"collections\":[]}".getBytes();

        assertThrows(IOException.class, () -> SessionManifestCodec.parse(noSession));
    }
}
