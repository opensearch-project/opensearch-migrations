package org.opensearch.migrations.transform;

import io.burt.jmespath.jcf.JcfRuntime;
public class JsonTransformerForOpenSearch23PlusTargetTransformerProvider extends JsonJMESPathTransformerProvider {
    public JsonJMESPathTransformer createTransformer(Object jsonConfig) {
        String script = "{ \"settings\": settings, \"mappings\": payload.mappings.*.properties | [merge(@)] }";
        return new JsonJMESPathTransformer(new JcfRuntime(), script);
    }
}
