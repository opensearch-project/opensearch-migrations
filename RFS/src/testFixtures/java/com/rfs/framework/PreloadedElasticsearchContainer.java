package com.rfs.framework;

import java.io.IOException;

public class PreloadedElasticsearchContainer extends ElasticsearchContainer {
    public PreloadedElasticsearchContainer(ElasticsearchContainer.Version baseVersion,
                                           String serverAlias,
                                           String dataLoaderImageName,
                                           String[] generatorArgs,
                                           boolean pullIfUnavailable) throws InterruptedException, IOException
    {
        super(new ElasticsearchVersion(
                new PreloadedDataContainerOrchestrator(baseVersion, serverAlias, dataLoaderImageName, generatorArgs)
                        .getReadyImageName(pullIfUnavailable),
                baseVersion.prettyName + "_preloaded"));
    }

    public PreloadedElasticsearchContainer(ElasticsearchContainer.Version baseVersion,
                                           String serverAlias,
                                           String dataLoaderImageName,
                                           String[] generatorArgs) throws Exception {
        this(baseVersion, serverAlias, dataLoaderImageName, generatorArgs, true);
    }
}
