package org.opensearch.migrations.transform;

public class Es2OsPost23 implements IJsonTransformerProvider {

    public JsonJMESPathTransformer createTransformer(Object jsonConfig) {
        String script = "{ \"settings\": settings, \"mappings\": payload.mappings.*.properties | [merge(@)] }";
        return new JsonJMESPathTransformer(script);
    }
}
