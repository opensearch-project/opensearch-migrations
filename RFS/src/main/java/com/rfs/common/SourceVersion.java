package com.rfs.common;

import org.opensearch.migrations.Version;

import com.beust.jcommander.Parameter;

public class SourceVersion {
    
    @Parameter(
        names = {"--source-version"},
        required = true,
        description = "The version information of the source cluster",
        converter = VersionConverter.class)
    public Version version;

}
