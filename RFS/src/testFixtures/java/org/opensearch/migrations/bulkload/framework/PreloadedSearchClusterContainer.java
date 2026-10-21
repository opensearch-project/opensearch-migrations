package org.opensearch.migrations.bulkload.framework;

import java.io.IOException;

import org.opensearch.migrations.testfixtures.SearchClusterContainer;

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
                baseVersion.getVersion()
            ) {
                @Override
                public String toString() {
                    return super.toString() + "_preloaded";
                }
            }
        );
    }
}
