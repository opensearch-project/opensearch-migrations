package com.rfs.common;

import org.opensearch.migrations.Version;

import com.beust.jcommander.Parameter;

public class TargetVersion {
    
    @Parameter(
        names = {"--target-version"},
        required = true,
        description = "The version information of the target cluster",
        converter = VersionConverter.class)
    public Version version;

}
