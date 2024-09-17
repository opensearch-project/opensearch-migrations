package org.opensearch.migrations.dashboards.converter;

import java.util.List;

import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

/**
 * Class Query defines the migration for the search object type between ES and OpenSearch.
 *
 * Source ES: https://github.com/elastic/kibana/blob/main/src/plugins/data/server/saved_objects/query.ts
 * Source OpenSearch: https://github.com/opensearch-project/OpenSearch-Dashboards/blob/main/src/plugins/data/server/saved_objects/query.ts
 */
public class QueryConverter extends SavedObjectConverter<SavedObject> {

    public QueryConverter() {
        super();
        this.dynamic = DynamicMapping.STRICT;
        this.allowedAttributes = List.of("title", "description", "query", "filters", "timefilter");
    }

    @Override
    public SavedObject convert(SavedObject savedObject) {
        // there are no query versions in OpenSearch
        savedObject.clearMigrationVersion();

        return super.convert(savedObject);
    }
}
