package org.opensearch.migrations.bulkload.version_es_5_6;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.version_universal.RemoteReaderClient;

public class RemoteReaderClient_ES_5_6 extends RemoteReaderClient {

    public RemoteReaderClient_ES_5_6(ConnectionContext connection) {
        super(connection);
    }

    @Override
    protected Map<String, String> getTemplateEndpoints() {
        return Map.of(
            "templates", "_template"
        );
    }
}
