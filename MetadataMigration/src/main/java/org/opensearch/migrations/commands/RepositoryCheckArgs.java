package org.opensearch.migrations.commands;

import org.opensearch.migrations.cli.OutputFormat;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

@Parameters(
    commandNames = "check-repository",
    commandDescription = "Checks list and read access to a configured snapshot repository"
)
public class RepositoryCheckArgs {
    @Parameter(names = {"--help", "-h"}, help = true, description = "Displays information about this command")
    public boolean help;

    @Parameter(
        names = {"--output"},
        description = "Output format: human-readable (default) or json",
        converter = OutputFormat.OutputFormatConverter.class
    )
    public OutputFormat outputFormat = OutputFormat.HUMAN_READABLE;

    @Parameter(
        names = {"--outputFile", "--output-file"},
        description = "Optional file path to write the command output to"
    )
    public String outputFile;

    @Parameter(
        names = {"--repo-uri", "--s3-repo-uri", "--file-system-repo-path"},
        required = true,
        description = "Repository URI. Schemes: file:///path, s3://bucket/path, gs://bucket/path"
    )
    public String repoUri;

    @Parameter(
        names = {"--local-dir", "--s3-local-dir", "--gcs-local-dir"},
        description = "Local directory for provider clients; a temporary directory is used when omitted"
    )
    public String localDir;

    @Parameter(
        names = {"--s3-region"},
        description = "AWS Region for an S3 repository"
    )
    public String s3Region;

    @Parameter(
        names = {"--endpoint", "--s3-endpoint", "--s3Endpoint"},
        description = "Custom repository endpoint, such as LocalStack or a GCS emulator"
    )
    public String endpoint;
}
