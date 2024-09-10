package com.rfs.framework;

import java.io.IOException;

public class PreloadedSearchClusterContainer extends SearchClusterContainer {
    public PreloadedSearchClusterContainer(
        SearchClusterContainer.ContainerVersion baseVersion,
        String serverAlias,
        String dataLoaderImageName,
        String[] generatorArgs
    ) throws InterruptedException, IOException {
        super(
            new ElasticsearchVersion(
                new PreloadedDataContainerOrchestrator(baseVersion, serverAlias, dataLoaderImageName, generatorArgs)
                    .getReadyImageName(true),
                baseVersion.version
            ) {
                @Override
                public String toString() {
                    return super.toString() + "_preloaded";
                }
            }
        );
    }
}
