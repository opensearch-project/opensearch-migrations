package com.rfs.common;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.ParameterException;

/**
 * An enumerated type used to refer to the software versions of the source and target clusters.
 */
public enum ClusterVersion {
    ES_6_8,
    ES_7_10,
    OS_2_11;

    public static class ArgsConverter implements IStringConverter<ClusterVersion> {
        @Override
        public ClusterVersion convert(String value) {
            switch (value) {
                case "es_6_8":
                    return ClusterVersion.ES_6_8;
                case "es_7_10":
                    return ClusterVersion.ES_7_10;
                case "os_2_11":
                    return ClusterVersion.OS_2_11;
                default:
                    throw new ParameterException("Invalid source version: " + value);
            }
        }
    }

    public static ClusterVersion fromInt(int versionId) {
        String versionString = Integer.toString(versionId);
        if (versionString.startsWith("608")) {
            return ES_6_8;
        } else if (versionString.startsWith("710")) {
            return ES_7_10;
        }
        // temp bypass for 717 to 710
        else if (versionString.startsWith("717")) {
            return ES_7_10;
        } else {
            throw new IllegalArgumentException("Invalid version: " + versionId);
        }
    }
}
