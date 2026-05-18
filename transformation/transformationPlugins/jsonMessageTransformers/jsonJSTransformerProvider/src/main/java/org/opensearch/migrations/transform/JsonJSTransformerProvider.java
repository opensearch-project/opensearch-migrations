package org.opensearch.migrations.transform;

import java.util.Map;

public class JsonJSTransformerProvider extends ScriptTransformerProvider {

    @Override
    protected String getLanguageName() {
        return "JavaScript";
    }

    @Override
    protected IJsonTransformer buildTransformer(
            ResolvedScript script, Object bindingsObject, Map<String, Object> config) {
        return new JavascriptTransformer(script.source(), bindingsObject);
    }
}
