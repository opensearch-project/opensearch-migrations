package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class JsonJSTransformerProvider implements IJsonTransformerProvider {

    public static final String INITIALIZATION_SCRIPT = "initializationScript";
    public static final String INVOCATION_SCRIPT = "invocationScript";
    public static final String BINDINGS_PROVIDER = "bindingsProvider";

    public final static ObjectMapper mapper = new ObjectMapper();

    @SneakyThrows
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        if (jsonConfig == null || (jsonConfig instanceof String && ((String) jsonConfig).isEmpty())) {
            throw new IllegalArgumentException("Configuration must contain 'initializationScript', 'invocationScript', and 'bindingsProvider'.");
        } else if (!(jsonConfig instanceof Map)) {
            throw new IllegalArgumentException(getConfigUsageStr());
        }

        var config = (Map<String, Object>) jsonConfig;
        String initializationScript = (String) config.get(INITIALIZATION_SCRIPT);
        String invocationScript = (String) config.get(INVOCATION_SCRIPT);
        Function<Object, Object> bindingsProvider = (Function<Object, Object>) config.get(BINDINGS_PROVIDER);

        if (initializationScript == null || invocationScript == null || bindingsProvider == null) {
            throw new IllegalArgumentException("'initializationScript', 'invocationScript', and 'bindingsProvider' must be provided.");
        }

        return new JavascriptTransformer(initializationScript, invocationScript, bindingsProvider);
    }

    private String getConfigUsageStr() {
        return this.getClass().getName() + " expects the incoming configuration to be a Map<String, Object>, " +
                "with values '" + INITIALIZATION_SCRIPT + "', '" + INVOCATION_SCRIPT + "', and '" + BINDINGS_PROVIDER + "'.";
    }
}
