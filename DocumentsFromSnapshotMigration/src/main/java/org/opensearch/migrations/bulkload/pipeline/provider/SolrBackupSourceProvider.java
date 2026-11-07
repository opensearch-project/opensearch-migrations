package org.opensearch.migrations.bulkload.pipeline.provider;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

import org.opensearch.migrations.SolrBackupDiscovery;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.S3Uri;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.pipeline.spi.CoordinationRequirement;
import org.opensearch.migrations.bulkload.pipeline.spi.DocumentSourceProvider;
import org.opensearch.migrations.bulkload.pipeline.spi.SourceRuntime;
import org.opensearch.migrations.bulkload.solr.SolrBackupLayout;
import org.opensearch.migrations.bulkload.solr.SolrMultiCollectionSource;
import org.opensearch.migrations.bulkload.solr.SolrShardPartition;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves documents from a Solr backup, on disk or in S3.
 *
 * <p>Construction lists the backup and downloads its metadata, hence
 * {@link #deferUntilWorkAvailable()}: a restarted pod shouldn't redo that to find no work left.
 */
@Slf4j
public class SolrBackupSourceProvider implements DocumentSourceProvider<SolrBackupSourceSpec> {

    public static final String KIND = "solr-backup";

    private static final int MIN_SUPPORTED_SOLR_MAJOR = 6;
    private static final int MAX_SUPPORTED_SOLR_MAJOR = 9;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public SolrBackupSourceSpec parseSpec(JsonNode config) {
        return SolrBackupSourceSpec.fromJson(config);
    }

    @Override
    public void validate(SolrBackupSourceSpec spec, SourceRuntime runtime) {
        var parsed = RepoUri.parse(spec.repoUri());
        if (!(parsed instanceof RepoUri.FileRepoUri) && !(parsed instanceof RepoUri.S3RepoUri)) {
            throw new IllegalArgumentException(
                "A Solr backup must be given as a file:// or s3:// URI, got: " + spec.repoUri());
        }
        if (spec.solrMajorVersion() < MIN_SUPPORTED_SOLR_MAJOR || spec.solrMajorVersion() > MAX_SUPPORTED_SOLR_MAJOR) {
            throw new IllegalArgumentException("Unsupported Solr major version: " + spec.solrMajorVersion()
                + " (supported: " + MIN_SUPPORTED_SOLR_MAJOR + "-" + MAX_SUPPORTED_SOLR_MAJOR + ")");
        }
    }

    @Override
    public boolean deferUntilWorkAvailable() {
        return true;
    }

    /** A Solr backup has no target cluster to hold work leases, so one must be supplied. */
    @Override
    public CoordinationRequirement coordinationRequirement() {
        return CoordinationRequirement.EXTERNAL_REQUIRED;
    }

    /** An s3:// backup is downloaded before it can be read; a file:// one is read where it sits. */
    @Override
    public boolean requiresScratchDirectory(SolrBackupSourceSpec spec) {
        return RepoUri.parse(spec.repoUri()) instanceof RepoUri.S3RepoUri;
    }

    /** Segments are read out of the backup in place; nothing is unpacked. */
    @Override
    public boolean requiresWorkingDirectory(SolrBackupSourceSpec spec) {
        return false;
    }

    @Override
    public DocumentSource create(SolrBackupSourceSpec spec, SourceRuntime runtime) throws IOException {
        Path backupDir;
        S3Repo s3Repo = null;
        switch (RepoUri.parse(spec.repoUri())) {
            case RepoUri.FileRepoUri f -> {
                backupDir = Paths.get(f.path());
                log.atInfo().setMessage("Starting Solr backup document migration from local dir: {}")
                    .addArgument(backupDir).log();
            }
            case RepoUri.S3RepoUri s -> {
                var backupS3Uri = SolrBackupLayout.buildBackupS3Uri(s.s3Uri(), spec.backupName());
                log.atInfo().setMessage("Downloading Solr backup metadata from S3: {}")
                    .addArgument(backupS3Uri).log();
                s3Repo = S3Repo.createRaw(
                    runtime.scratchDir(),
                    new S3Uri(backupS3Uri),
                    spec.s3Region(),
                    spec.endpoint() != null ? URI.create(spec.endpoint()) : null);
                backupDir = s3Repo.getRepoRootDir();
            }
            // validate() has already rejected every other scheme.
            default -> throw new IllegalArgumentException(
                "A Solr backup must be given as a file:// or s3:// URI, got: " + spec.repoUri());
        }

        var discovery = SolrBackupDiscovery.discover(s3Repo, backupDir, spec.indexAllowlist());
        Consumer<String> collectionPreparer = discovery::prepareCollection;
        Consumer<SolrShardPartition> shardPreparer = discovery.shardPreparationNeeded()
            ? discovery::prepareShard : null;

        return new SolrMultiCollectionSource(
            backupDir,
            discovery.schemas(),
            collectionPreparer,
            shardPreparer,
            spec.solrMajorVersion(),
            discovery.dataDirByCollection());
    }
}
