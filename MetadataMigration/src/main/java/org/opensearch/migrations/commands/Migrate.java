package org.opensearch.migrations.commands;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.opensearch.migrations.MetadataArgs;
import org.opensearch.migrations.metadata.tracing.RootMetadataMigrationContext;

import com.beust.jcommander.ParameterException;
import com.rfs.common.ClusterVersion;
import com.rfs.common.FileSystemRepo;
import com.rfs.common.OpenSearchClient;
import com.rfs.common.S3Repo;
import com.rfs.common.S3Uri;
import com.rfs.common.SnapshotRepo;
import com.rfs.common.SourceRepo;
import com.rfs.models.GlobalMetadata;
import com.rfs.models.IndexMetadata;
import com.rfs.transformers.TransformFunctions;
import com.rfs.transformers.Transformer;
import com.rfs.version_es_7_10.GlobalMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.IndexMetadataFactory_ES_7_10;
import com.rfs.version_es_7_10.SnapshotRepoProvider_ES_7_10;
import com.rfs.version_os_2_11.GlobalMetadataCreator_OS_2_11;
import com.rfs.version_os_2_11.IndexCreator_OS_2_11;
import com.rfs.worker.IndexRunner;
import com.rfs.worker.MetadataRunner;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Migrate {

    static final int INVALID_PARAMETER_CODE = 999;
    static final int UNEXPECTED_FAILURE_CODE = 888;
    private final MetadataArgs arguments;

    public Migrate(MetadataArgs arguments) {
        this.arguments = arguments;
    }

    public MigrateResult execute(RootMetadataMigrationContext context) {
        log.atInfo().setMessage("Command line arguments {0}").addArgument(arguments::toString).log();
        try {
            if (arguments.fileSystemRepoPath == null && arguments.s3RepoUri == null) {
                throw new ParameterException("Either file-system-repo-path or s3-repo-uri must be set");
            }
            if (arguments.fileSystemRepoPath != null && arguments.s3RepoUri != null) {
                throw new ParameterException("Only one of file-system-repo-path and s3-repo-uri can be set");
            }
            if ((arguments.s3RepoUri != null) && (arguments.s3Region == null || arguments.s3LocalDirPath == null)) {
                throw new ParameterException("If an s3 repo is being used, s3-region and s3-local-dir-path must be set");
            } 
        } catch (Exception e) {
            log.atError().setMessage("Invalid parameter").setCause(e).log();
            return new MigrateResult(INVALID_PARAMETER_CODE);
        }

        final String snapshotName = arguments.snapshotName;
        final Path fileSystemRepoPath = arguments.fileSystemRepoPath != null
            ? Paths.get(arguments.fileSystemRepoPath)
            : null;
        final Path s3LocalDirPath = arguments.s3LocalDirPath != null ? Paths.get(arguments.s3LocalDirPath) : null;
        final String s3RepoUri = arguments.s3RepoUri;
        final String s3Region = arguments.s3Region;
        final List<String> indexAllowlist = arguments.indexAllowlist;
        final List<String> indexTemplateAllowlist = arguments.indexTemplateAllowlist;
        final List<String> componentTemplateAllowlist = arguments.componentTemplateAllowlist;
        final int awarenessDimensionality = arguments.minNumberOfReplicas + 1;

        try {
            log.info("Running RfsWorker");
            final OpenSearchClient targetClient = new OpenSearchClient(arguments.targetArgs.toConnectionContext());

            final SourceRepo sourceRepo = fileSystemRepoPath != null
                ? new FileSystemRepo(fileSystemRepoPath)
                : S3Repo.create(s3LocalDirPath, new S3Uri(s3RepoUri), s3Region);
            final SnapshotRepo.Provider repoDataProvider = new SnapshotRepoProvider_ES_7_10(sourceRepo);
            final GlobalMetadata.Factory metadataFactory = new GlobalMetadataFactory_ES_7_10(repoDataProvider);
            final GlobalMetadataCreator_OS_2_11 metadataCreator = new GlobalMetadataCreator_OS_2_11(
                targetClient,
                List.of(),
                componentTemplateAllowlist,
                indexTemplateAllowlist,
                context.createMetadataMigrationContext()
            );
            final Transformer transformer = TransformFunctions.getTransformer(
                ClusterVersion.ES_7_10,
                ClusterVersion.OS_2_11,
                awarenessDimensionality
            );
            new MetadataRunner(snapshotName, metadataFactory, metadataCreator, transformer).migrateMetadata();
            log.info("Metadata copy complete.");

            final IndexMetadata.Factory indexMetadataFactory = new IndexMetadataFactory_ES_7_10(repoDataProvider);
            final IndexCreator_OS_2_11 indexCreator = new IndexCreator_OS_2_11(targetClient);
            new IndexRunner(
                snapshotName,
                indexMetadataFactory,
                indexCreator,
                transformer,
                indexAllowlist,
                context.createIndexContext()
            ).migrateIndices();
            log.info("Index copy complete.");
        } catch (Throwable e) {
            log.atError().setMessage("Unexpected failure").setCause(e).log();
            return new MigrateResult(UNEXPECTED_FAILURE_CODE);
        }

        return new MigrateResult(0);
    }
}
