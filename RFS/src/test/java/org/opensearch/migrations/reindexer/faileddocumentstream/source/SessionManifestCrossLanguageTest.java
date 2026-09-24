package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Pins the manifest's canonical encoding against the console's sealer.
 *
 * <p>Both can seal the same session, and the loser compares digests, so a difference here would
 * make a consistent session look like one that was still being written.
 *
 * <p>{@code test_manifest_encoding_matches_the_java_sealer} asserts the same literal.
 */
class SessionManifestCrossLanguageTest {

    /** Unsorted input, a non-ASCII session id, and a key needing an escape. */
    private static final SessionManifest FIXTURE = new SessionManifest(1, "session-á", List.of(
        new SessionManifest.CollectionEntry("users", List.of(
            new SessionManifest.PartitionEntry("worker-2", List.of("p/index=users/worker-2/b.ndjson.gz")))),
        new SessionManifest.CollectionEntry("orders-2024", List.of(
            new SessionManifest.PartitionEntry("worker-10", List.of(
                "p/index=orders-2024/worker-10/z.ndjson.gz",
                "p/index=orders-2024/worker-10/a\"quote.ndjson.gz")),
            new SessionManifest.PartitionEntry("worker-1", List.of(
                "p/index=orders-2024/worker-1/a.ndjson.gz"))))));

    static final String EXPECTED_CANONICAL =
        "{\"schemaVersion\":1,\"sessionId\":\"session-á\",\"collections\":["
            + "{\"name\":\"orders-2024\",\"partitions\":["
            + "{\"name\":\"worker-1\",\"objectKeys\":[\"p/index=orders-2024/worker-1/a.ndjson.gz\"]},"
            + "{\"name\":\"worker-10\",\"objectKeys\":["
            + "\"p/index=orders-2024/worker-10/a\\\"quote.ndjson.gz\","
            + "\"p/index=orders-2024/worker-10/z.ndjson.gz\"]}]},"
            + "{\"name\":\"users\",\"partitions\":["
            + "{\"name\":\"worker-2\",\"objectKeys\":[\"p/index=users/worker-2/b.ndjson.gz\"]}]}]}";

    /** Asserted on both sides. */
    static final String EXPECTED_DIGEST =
        "542cd39d37cb446b2fa43f3554a85fbe2571d16f682205f157f2d61609fcb714";

    @Test
    void encodesCanonically() {
        assertThat(SessionManifestCodec.toCanonicalString(FIXTURE), equalTo(EXPECTED_CANONICAL));
    }

    @Test
    void digestsTheCanonicalBytes() {
        assertThat(SessionManifestCodec.digest(FIXTURE), equalTo(EXPECTED_DIGEST));
        assertThat(SessionManifestCodec.digest(EXPECTED_CANONICAL.getBytes(StandardCharsets.UTF_8)),
            equalTo(EXPECTED_DIGEST));
    }
}
