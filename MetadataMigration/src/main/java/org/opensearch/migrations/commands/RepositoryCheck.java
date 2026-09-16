package org.opensearch.migrations.commands;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.bulkload.common.GcsRepo;
import org.opensearch.migrations.bulkload.common.GcsUri;
import org.opensearch.migrations.bulkload.common.RepoUri;
import org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult;
import org.opensearch.migrations.bulkload.common.S3Repo;

import com.beust.jcommander.ParameterException;

import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.failed;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.failureMessage;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.partial;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.passed;
import static org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult.skipped;

public class RepositoryCheck {
    private final RepositoryCheckArgs arguments;

    public RepositoryCheck(RepositoryCheckArgs arguments) {
        this.arguments = arguments;
    }

    public RepositoryCheckResult execute() {
        var parsedUri = RepoUri.parse(arguments.repoUri);
        Path localDir = resolveLocalDirectory();
        try {
            RepositoryAccessCheckResult check = switch (parsedUri) {
                case RepoUri.FileRepoUri fileRepo -> checkFileRepository(Path.of(fileRepo.path()));
                case RepoUri.GcsRepoUri gcsRepo -> GcsRepo.create(
                    localDir,
                    new GcsUri(gcsRepo.rawUri()),
                    arguments.endpoint,
                    null
                ).checkAccess();
                case RepoUri.S3RepoUri s3Repo -> checkS3Repository(localDir, s3Repo);
            };
            return new RepositoryCheckResult(check);
        } catch (RuntimeException e) {
            return new RepositoryCheckResult(setupFailure(parsedUri, e));
        }
    }

    private RepositoryAccessCheckResult checkS3Repository(
        Path localDir,
        RepoUri.S3RepoUri repo
    ) {
        if (arguments.s3Region == null || arguments.s3Region.isBlank()) {
            throw new ParameterException("--s3-region is required for an S3 repository");
        }
        URI endpoint = Optional.ofNullable(arguments.endpoint)
            .filter(value -> !value.isBlank())
            .map(URI::create)
            .orElse(null);
        try (var s3Repo = S3Repo.createRaw(
            localDir,
            repo.s3Uri(),
            arguments.s3Region,
            endpoint
        )) {
            return s3Repo.checkAccess();
        }
    }

    private Path resolveLocalDirectory() {
        if (arguments.localDir != null && !arguments.localDir.isBlank()) {
            return Path.of(arguments.localDir);
        }
        try {
            return Files.createTempDirectory("repository-access-check-");
        } catch (IOException e) {
            throw new ParameterException("Unable to create a temporary repository check directory", e);
        }
    }

    private RepositoryAccessCheckResult checkFileRepository(Path root) {
        var stages = new ArrayList<RepositoryAccessCheckResult.Stage>();
        if (!Files.isDirectory(root)) {
            stages.add(failed(
                "list-prefix",
                "List repository directory",
                "The configured repository path does not exist or is not a directory."
            ));
            stages.add(skipped(
                "read-object",
                "Read repository file",
                "File read was not attempted because the repository directory could not be listed."
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.FAILED,
                "file",
                root.toString(),
                "Unable to list the configured repository directory.",
                List.copyOf(stages)
            );
        }

        Optional<Path> firstFile;
        try (var files = Files.walk(root)) {
            firstFile = files.filter(Files::isRegularFile).findFirst();
            stages.add(passed(
                "list-prefix",
                "List repository directory",
                "The configured repository directory is accessible."
            ));
        } catch (IOException | RuntimeException e) {
            stages.add(failed(
                "list-prefix",
                "List repository directory",
                failureMessage(e)
            ));
            stages.add(skipped(
                "read-object",
                "Read repository file",
                "File read was not attempted because the repository directory could not be listed."
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.FAILED,
                "file",
                root.toString(),
                "Unable to list the configured repository directory.",
                List.copyOf(stages)
            );
        }

        if (firstFile.isEmpty()) {
            stages.add(partial(
                "read-object",
                "Read repository file",
                "No file is currently available in the repository to verify read access."
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.PARTIALLY_VERIFIED,
                "file",
                root.toString(),
                "The repository directory is accessible, but file read access could not yet be verified.",
                List.copyOf(stages)
            );
        }

        try (SeekableByteChannel channel = Files.newByteChannel(firstFile.get(), StandardOpenOption.READ)) {
            channel.read(ByteBuffer.allocate(1));
            stages.add(passed(
                "read-object",
                "Read repository file",
                "A file in the configured repository is readable."
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.VALID,
                "file",
                root.toString(),
                "The repository directory can be listed and its files can be read.",
                List.copyOf(stages)
            );
        } catch (IOException | RuntimeException e) {
            stages.add(failed(
                "read-object",
                "Read repository file",
                failureMessage(e)
            ));
            return new RepositoryAccessCheckResult(
                RepositoryAccessCheckResult.Status.FAILED,
                "file",
                root.toString(),
                "The repository directory is accessible, but a file could not be read.",
                List.copyOf(stages)
            );
        }
    }

    private RepositoryAccessCheckResult setupFailure(RepoUri repo, RuntimeException failure) {
        String provider = switch (repo) {
            case RepoUri.FileRepoUri ignored -> "file";
            case RepoUri.GcsRepoUri ignored -> "gcs";
            case RepoUri.S3RepoUri ignored -> "s3";
        };
        String message = failureMessage(failure);
        return new RepositoryAccessCheckResult(
            RepositoryAccessCheckResult.Status.FAILED,
            provider,
            repo.rawUri(),
            "The repository client could not be initialized.",
            List.of(
                failed("initialize-client", "Initialize repository client", message),
                skipped(
                    "list-prefix",
                    "List repository prefix",
                    "Repository access was not attempted because the client could not be initialized."
                ),
                skipped(
                    "read-object",
                    "Read repository object",
                    "Repository access was not attempted because the client could not be initialized."
                )
            )
        );
    }
}
