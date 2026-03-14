package org.opensearch.migrations;

import com.beust.jcommander.Parameter;
import lombok.Getter;

@Getter
public class VersionStrictness {
    public static final String ALLOW_LOOSE_VERSION_MATCHING_PARAM_KEY = "--allow-loose-version-matching";

    public static final String REMEDIATION_MESSAGE = "There was a problem matching versions, this might be worked around by adding '"
        + ALLOW_LOOSE_VERSION_MATCHING_PARAM_KEY + "' to the command line arguments";

    @Parameter(names = { ALLOW_LOOSE_VERSION_MATCHING_PARAM_KEY }, description = "Allow loose version matching for cluster version types that are"
        + " not officially supported. By default this is disabled.")
    public boolean allowLooseVersionMatches = false;

}
