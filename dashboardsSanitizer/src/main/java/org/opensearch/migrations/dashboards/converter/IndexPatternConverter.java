package org.opensearch.migrations.dashboards.converter;

import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

//
// Class IndexPattern defined the migration for the index-pattern object type between ES and OpenSearch.
//
// Source ES: https://github.com/elastic/kibana/blob/main/src/plugins/data_views/server/saved_objects/index_pattern_migrations.ts
// Source OpenSearch: https://github.com/opensearch-project/OpenSearch-Dashboards/blob/main/src/plugins/data/server/saved_objects/index_pattern_migrations.ts
//
public class IndexPatternConverter extends SavedObjectConverter<SavedObject> {

    public IndexPatternConverter() {
        super();

        this.addMigration("7.6.0", this::backport_addAllowNoIndex);
    }

    private void backport_addAllowNoIndex(SavedObject savedObject) {
        savedObject.attributes().remove("allowNoIndex");
    }
}