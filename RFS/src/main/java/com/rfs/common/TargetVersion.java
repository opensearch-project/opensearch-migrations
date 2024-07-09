package com.rfs.common;

import com.beust.jcommander.Parameter;

import src.org.opensearch.migrations.Version;

public class TargetVersion {
    
    @Parameter(
        names = {"--target-version"},
        required = true,
        description = "The version information of the target cluster",
        converter = VersionConverter.class)
    public Version version;

}
