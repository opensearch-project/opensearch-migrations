package com.rfs.common;

import com.beust.jcommander.Parameter;

import src.org.opensearch.migrations.Version;

public class SourceVersion {
    
    @Parameter(
        names = {"--source-version"},
        required = true,
        description = "The version information of the source cluster",
        converter = VersionConverter.class)
    public Version version;

}
