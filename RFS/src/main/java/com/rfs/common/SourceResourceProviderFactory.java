package com.rfs.common;

import com.rfs.version_es_6_8.SourceResourceProvider_ES_6_8;
import com.rfs.version_es_7_10.SourceResourceProvider_ES_7_10;

public class SourceResourceProviderFactory {
    public static SourceResourceProvider getProvider(ClusterVersion version) {
        switch (version) {
            case ES_6_8:
                return new SourceResourceProvider_ES_6_8();
            case ES_7_10:
                return new SourceResourceProvider_ES_7_10();
            case ES_7_17:
                // We don't currently distinguish between 7.10 and 7.17
                return new SourceResourceProvider_ES_7_10();
            default:
                throw new IllegalArgumentException("Invalid version: " + version);
        }
    }

}
