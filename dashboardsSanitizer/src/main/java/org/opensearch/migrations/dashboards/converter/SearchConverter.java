package org.opensearch.migrations.dashboards.converter;

import java.util.List;

import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

/**
 * Class Search defines the migration for the search object type between ES and OpenSearch.
 *
 * Source ES: https://github.com/elastic/kibana/blob/main/src/plugins/saved_search/server/saved_objects/search_migrations.ts
 * Source OpenSearch: https://github.com/opensearch-project/OpenSearch-Dashboards/blob/main/src/plugins/discover/server/saved_objects/search_migrations.ts
 */
public class SearchConverter extends SavedObjectConverter<SavedObject> {

    public SearchConverter() {
        this.dynamic = DynamicMapping.STRICT;
        this.allowedAttributes = List.of("columns", "description", "hits", "kibanaSavedObjectMeta", "sort", "title", "version");

        this.addMigration("7.9.3", this::backportNothing);
    }

}
