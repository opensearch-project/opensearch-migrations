package com.rfs.framework;

import java.io.IOException;

public class PreloadedSearchClusterContainer extends SearchClusterContainer {
    public PreloadedSearchClusterContainer(
        SearchClusterContainer.Version baseVersion,
        String serverAlias,
        String dataLoaderImageName,
        String[] generatorArgs,
        boolean pullIfUnavailable
    ) throws InterruptedException, IOException {
        super(
            new ElasticsearchVersion(
                new PreloadedDataContainerOrchestrator(baseVersion, serverAlias, dataLoaderImageName, generatorArgs)
                    .getReadyImageName(pullIfUnavailable),
                baseVersion.getSourceVersion(),
                baseVersion.prettyName + "_preloaded"
            )
        );
    }

    public PreloadedSearchClusterContainer(
        SearchClusterContainer.Version baseVersion,
        String serverAlias,
        String dataLoaderImageName,
        String[] generatorArgs
    ) throws Exception {
        this(baseVersion, serverAlias, dataLoaderImageName, generatorArgs, true);
    }
}
