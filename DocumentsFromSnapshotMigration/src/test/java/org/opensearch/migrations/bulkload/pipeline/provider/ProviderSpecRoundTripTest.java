package org.opensearch.migrations.bulkload.pipeline.provider;

import java.util.List;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DeltaMode;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceLocation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The caller builds a typed spec and hands the JSON to whichever provider the registry resolved, so
 * {@code toJson}/{@code parseSpec} round-tripping is the contract between them.
 */
class ProviderSpecRoundTripTest {

    private static final Version VERSION = Version.fromString("ES 7.10.2");

    private final EsSnapshotSourceProvider esProvider = new EsSnapshotSourceProvider();
    private final SolrBackupSourceProvider solrProvider = new SolrBackupSourceProvider();

    @Test
    void esSnapshotSourceSpec_roundTripsEveryFieldWhenAllAreSet() {
        var original = new EsSnapshotSourceSpec(
            "s3://bucket/snapshots", "nightly", Version.fromString("ES 7.10.2"),
            List.of("logs", "metrics"), "us-east-2", "http://localhost:4566",
            true, 4242L, true, EmitDocTypeMode.ON, "previous", DeltaMode.UPDATES_AND_DELETES, true);

        assertThat(esProvider.parseSpec(original.toJson()), equalTo(original));
    }

    /** Every optional field absent must survive as absent, not as the string "null". */
    @Test
    void esSnapshotSourceSpec_roundTripsWithEveryOptionalFieldAbsent() {
        var original = new EsSnapshotSourceSpec(
            "file:///snapshots", "nightly", null,
            List.of(), null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, null, false);

        var parsed = esProvider.parseSpec(original.toJson());

        assertThat(parsed, equalTo(original));
        assertThat(parsed.version(), nullValue());
        assertThat(parsed.s3Region(), nullValue());
        assertThat(parsed.endpoint(), nullValue());
        assertThat(parsed.previousSnapshotName(), nullValue());
        assertThat(parsed.deltaMode(), nullValue());
        assertThat(parsed.indexAllowlist(), empty());
    }

    @Test
    void esSnapshotSourceSpec_reportsWhetherItIsADeltaRead() {
        var delta = new EsSnapshotSourceSpec("file:///s", "n", null, List.of(), null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, "previous", DeltaMode.UPDATES_ONLY, false);
        var regular = new EsSnapshotSourceSpec("file:///s", "n", null, List.of(), null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, null, false);

        assertThat(delta.isDeltaRead(), is(true));
        assertThat(regular.isDeltaRead(), is(false));
        assertThat(delta.kind(), equalTo(EsSnapshotSourceProvider.KIND));
    }

    @Test
    void esSnapshotSourceSpec_rejectsMissingRequiredFields() {
        var noRepo = JsonNodeFactory.instance.objectNode().put("snapshotName", "nightly");
        var noSnapshot = JsonNodeFactory.instance.objectNode().put("repoUri", "file:///s");

        assertThrows(IllegalArgumentException.class, () -> esProvider.parseSpec(noRepo));
        assertThrows(IllegalArgumentException.class, () -> esProvider.parseSpec(noSnapshot));
    }

    /** A lone delta input is a user error the provider must catch before construction. */
    @Test
    void esSnapshotProvider_rejectsHalfConfiguredDeltaReads() {
        var previousOnly = new EsSnapshotSourceSpec("file:///s", "n", VERSION, List.of(), null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, "previous", null, false);
        var modeOnly = new EsSnapshotSourceSpec("file:///s", "n", VERSION, List.of(), null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, DeltaMode.UPDATES_ONLY, false);

        assertThrows(IllegalArgumentException.class, () -> esProvider.validateSpec(previousOnly));
        assertThrows(IllegalArgumentException.class, () -> esProvider.validateSpec(modeOnly));
    }

    @Test
    void esSnapshotProvider_requiresAVersion() {
        var noVersion = new EsSnapshotSourceSpec("file:///s", "n", null, List.of(), null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, null, false);

        assertThrows(IllegalArgumentException.class, () -> esProvider.validateSpec(noVersion));
    }

    @Test
    void esSnapshotProvider_requiresARegionForAnS3Repo() {
        var noRegion = new EsSnapshotSourceSpec("s3://bucket/repo", "n", VERSION, List.of(), null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, null, false);
        var withRegion = new EsSnapshotSourceSpec("s3://bucket/repo", "n", VERSION, List.of(), "us-east-2", null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, null, false);

        assertThrows(IllegalArgumentException.class, () -> esProvider.validateSpec(noRegion));
        esProvider.validateSpec(withRegion);
    }

    @Test
    void solrBackupProvider_requiresARegionForAnS3Backup() {
        var noRegion = new SolrBackupSourceSpec("s3://bucket/backups", "n", 8, List.of(), null, null);

        assertThrows(IllegalArgumentException.class, () -> solrProvider.validateSpec(noRegion));
    }

    @Test
    void specs_reportWhereTheyRead() {
        var es = new EsSnapshotSourceSpec("s3://bucket/repo", "nightly", VERSION, List.of(), "us-east-2", null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, null, false);
        var solr = new SolrBackupSourceSpec("s3://bucket/backups", "nightly", 8, List.of(), "us-east-2", null);
        var redrive = new FailedDocumentStreamSourceSpec(
            "s3://bucket/stream", "session", "us-east-2", null, List.of(), List.of());

        assertThat(es.location(), equalTo(new SourceLocation("nightly", "s3://bucket/repo", "us-east-2")));
        assertThat(solr.location(), equalTo(new SourceLocation("nightly", "s3://bucket/backups", "us-east-2")));
        assertThat(redrive.location(), equalTo(new SourceLocation("session", "s3://bucket/stream", "us-east-2")));
    }

    @Test
    void esSnapshotSourceSpec_defaultsANullEmitDocTypeToAuto() {
        var spec = new EsSnapshotSourceSpec("file:///s", "n", null, List.of(), null, null,
            false, 0L, false, null, null, null, false);

        assertThat(spec.emitDocType(), equalTo(EmitDocTypeMode.AUTO));
    }

    @Test
    void esSnapshotProvider_isNotDeferred() {
        assertThat(esProvider.deferUntilWorkAvailable(), is(false));
        assertThat(esProvider.kind(), equalTo(EsSnapshotSourceProvider.KIND));
    }

    @Test
    void solrBackupSourceSpec_roundTripsEveryFieldWhenAllAreSet() {
        var original = new SolrBackupSourceSpec(
            "s3://bucket/backups", "nightly", 8, List.of("catalog"), "us-east-2", "http://localhost:4566");

        assertThat(solrProvider.parseSpec(original.toJson()), equalTo(original));
    }

    @Test
    void solrBackupSourceSpec_roundTripsWithEveryOptionalFieldAbsent() {
        var original = new SolrBackupSourceSpec("file:///backups", null, 9, List.of(), null, null);

        var parsed = solrProvider.parseSpec(original.toJson());

        assertThat(parsed, equalTo(original));
        assertThat(parsed.backupName(), nullValue());
        assertThat(parsed.s3Region(), nullValue());
        assertThat(parsed.endpoint(), nullValue());
        assertThat(parsed.indexAllowlist(), empty());
        assertThat(parsed.kind(), equalTo(SolrBackupSourceProvider.KIND));
    }

    @Test
    void solrBackupSourceSpec_rejectsMissingRepoUri() {
        var noRepo = JsonNodeFactory.instance.objectNode().put("solrMajorVersion", 8);

        assertThrows(IllegalArgumentException.class, () -> solrProvider.parseSpec(noRepo));
    }

    /** A null allowlist is normalized to empty so callers never have to null-check it. */
    @Test
    void specs_normalizeANullAllowlistToEmpty() {
        var es = new EsSnapshotSourceSpec("file:///s", "n", null, null, null, null,
            false, 0L, false, EmitDocTypeMode.AUTO, null, null, false);
        var solr = new SolrBackupSourceSpec("file:///b", null, 9, null, null, null);

        assertThat(es.indexAllowlist(), empty());
        assertThat(solr.indexAllowlist(), empty());
    }
}
