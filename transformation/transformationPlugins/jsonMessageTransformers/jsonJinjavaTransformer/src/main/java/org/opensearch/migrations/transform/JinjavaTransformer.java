package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.opensearch.migrations.transform.jinjava.NameMappingClasspathResourceLocator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.loader.ResourceLocator;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JinjavaTransformer implements IJsonTransformer {

    protected final static ObjectMapper objectMapper = new ObjectMapper();

    protected final Jinjava jinjava;
    protected final Function<Map<String, Object>, Map<String, Object>> createContextWithSourceFunction;
    private final String templateStr;

    public JinjavaTransformer(String templateString,
                              Function<Map<String, Object>, Map<String, Object>> contextProviderFromSource) {
        this(templateString, contextProviderFromSource, new NameMappingClasspathResourceLocator());
    }

    public JinjavaTransformer(String templateString,
                              Function<Map<String, Object>, Map<String, Object>> createContextWithSource,
                              ResourceLocator resourceLocator)
    {
        jinjava = new Jinjava();
        this.createContextWithSourceFunction = createContextWithSource;
        jinjava.setResourceLocator(resourceLocator);
        this.templateStr = templateString;
    }

    @SneakyThrows
    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        var resultStr = jinjava.render(templateStr, createContextWithSourceFunction.apply(incomingJson));
        log.atInfo().setMessage("output from jinjava... {}").addArgument(resultStr).log();
        var parsedObj = (Map<String,Object>) objectMapper.readValue(resultStr, Map.class);
        return doFinalSubstitutions(incomingJson, parsedObj);
    }

    private Map<String, Object> doFinalSubstitutions(Map<String, Object> incomingJson, Map<String, Object> parsedObj) {
        return Optional.ofNullable(parsedObj.get(JsonKeysForHttpMessage.PRESERVE_KEY)).filter(v->v.equals("*"))
            .map(star -> incomingJson)
            .orElseGet(() -> {
                findAndReplacePreserves(incomingJson, parsedObj);
                findAndReplacePreserves((Map<String, Object>) incomingJson.get(JsonKeysForHttpMessage.PAYLOAD_KEY),
                    (Map<String, Object>) parsedObj.get(JsonKeysForHttpMessage.PAYLOAD_KEY));
                return parsedObj;
            });
    }

    private void findAndReplacePreserves(Map<String, Object> incomingRoot, Map<String, Object> parsedRoot) {
        if (parsedRoot == null || incomingRoot == null) {
            return;
        }
        var preserveKeys = (List<String>) parsedRoot.get(JsonKeysForHttpMessage.PRESERVE_KEY);
        if (preserveKeys != null) {
            preserveKeys.forEach(preservedKey ->
                parsedRoot.put(preservedKey, incomingRoot.get(preservedKey)));
            parsedRoot.remove(JsonKeysForHttpMessage.PRESERVE_KEY);
        }
    }
}
