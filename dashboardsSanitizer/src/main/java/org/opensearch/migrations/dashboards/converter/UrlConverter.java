package org.opensearch.migrations.dashboards.converter;

import org.opensearch.migrations.dashboards.savedobjects.SavedObject;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * Class URL defines the migration for the search object type between ES and OpenSearch.
 *
 * Source ES: https://github.com/elastic/kibana/blob/main/src/plugins/share/server/url_service/saved_objects/register_url_service_saved_object_type.ts
 * Source OpenSearch: https://github.com/opensearch-project/OpenSearch-Dashboards/blob/main/src/plugins/share/server/saved_objects/url.ts
 */
@Slf4j
public class UrlConverter extends SavedObjectConverter<SavedObject> {

    public UrlConverter() {
        super();

        this.addMigration("0.0.0", this::backportToOld);
    }

    public void backportToOld(SavedObject savedObject) {

        // url service sub system has been reworked in Kibana.
        // The service allows to self-register locator services
        // The OpenSearch Dashboard does support only the LegacyShortURLLocator way of URL generation
        // setMigrationVersion("7.9.3");

        final ObjectNode locator = savedObject.attributeValueAsJson("locatorJSON");

        if (locator != null) {
            savedObject.attributes().put("url", locator.at("/state/url").asText());

            savedObject.attributes().remove("locatorJSON");
            savedObject.attributes().remove("slug");
        }

    }
}
