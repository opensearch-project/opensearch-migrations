package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.loader.FileLocator;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JinjavaTransformer implements IJsonTransformer {

    protected final static ObjectMapper objectMapper = new ObjectMapper();
    protected final String templateString;
    protected final Jinjava jinjava;
    protected final Function<Map<String, Object>, Map<String, Object>> wrapSourceAsContextConverter;

    public JinjavaTransformer(String templateString,
                              Function<Map<String, Object>, Map<String, Object>> contextProviderFromSource) {
        this(templateString,  contextProviderFromSource, null);
    }

    public JinjavaTransformer(String templateString,
                              Function<Map<String, Object>, Map<String, Object>> wrapSourceAsContextConverter,
                              FileLocator fileLocator)
    {
        this.templateString = templateString;
        this.wrapSourceAsContextConverter = wrapSourceAsContextConverter;
        this.jinjava = new Jinjava();
        this.jinjava.setResourceLocator(fileLocator);

    }

    @SneakyThrows
    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        String resultStr = jinjava.render(templateString, wrapSourceAsContextConverter.apply(incomingJson));
        log.atInfo().setMessage("output = {}").addArgument(resultStr).log();
        return objectMapper.readValue(resultStr, Map.class);
    }
}
