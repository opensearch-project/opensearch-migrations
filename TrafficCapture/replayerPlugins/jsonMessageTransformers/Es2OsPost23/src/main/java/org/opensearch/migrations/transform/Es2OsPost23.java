package org.opensearch.migrations.transform;

public class Es2OsPost23 implements IJsonTransformerProvider {

    public JsonJMESPathTransformer createTransformer(Object jsonConfig) {
        String script = "{\"settings\": settings, \"mappings\": {\"properties\": mappings.my_type.properties}}";
        return new JsonJMESPathTransformer(script);
    }
}
