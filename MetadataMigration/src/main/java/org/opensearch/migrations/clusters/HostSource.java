package org.opensearch.migrations.clusters;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class HostSource implements SourceCluster {

    private final String url;

    @Override
    public boolean isAvaliable() {
        return true;
    }
    
}
