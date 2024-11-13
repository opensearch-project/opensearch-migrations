package org.opensearch.migrations.transform;

import java.io.File;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.loader.FileLocator;
import lombok.SneakyThrows;

public class JinjavaTransformer implements IJsonTransformer {

    protected final static ObjectMapper objectMapper = new ObjectMapper();
    protected final String templateString;
    protected final Jinjava jinjava;
    protected final Function<Map<String, Object>, Map<String, Object>> wrapSourceAsContextConverter;

    public JinjavaTransformer(String templateString,
                              Function<Map<String, Object>, Map<String, Object>> wrapSourceAsContextConverter) {
        this(templateString,  wrapSourceAsContextConverter, null);
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
        return objectMapper.readValue(resultStr, Map.class);
    }
}
