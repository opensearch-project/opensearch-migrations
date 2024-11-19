package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.function.Function;

import org.opensearch.migrations.transform.jinjava.InlineTemplateResourceLocator;
import org.opensearch.migrations.transform.jinjava.NameMappingClasspathResourceLocator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.interpret.Context;
import com.hubspot.jinjava.interpret.JinjavaInterpreter;
import com.hubspot.jinjava.loader.CascadingResourceLocator;
import com.hubspot.jinjava.loader.ResourceLocator;
import com.hubspot.jinjava.tree.Node;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JinjavaTransformer implements IJsonTransformer {

    protected final static ObjectMapper objectMapper = new ObjectMapper();

    protected final Jinjava jinjava;
    protected final ThreadLocal<JinjavaInterpreter> threadLocalInterpreter;
    protected final Node template;
    protected final Function<Map<String, Object>, Map<String, Object>> wrapSourceAsContextConverter;

    public JinjavaTransformer(String templateString,
                              Map<String, Object> baseImmutableBindings,
                              Function<Map<String, Object>, Map<String, Object>> contextProviderFromSource) {
        this(templateString, baseImmutableBindings, contextProviderFromSource, new NameMappingClasspathResourceLocator());
    }

    public JinjavaTransformer(String templateString,
                              Map<String, Object> baseImmutableBindings,
                              Function<Map<String, Object>, Map<String, Object>> contextProviderFromSource,
                              @NonNull Map<String, String> inlineTemplates) {
        this(templateString, baseImmutableBindings,  contextProviderFromSource, new CascadingResourceLocator(
            new NameMappingClasspathResourceLocator(),
            new InlineTemplateResourceLocator(inlineTemplates)));
    }

    public JinjavaTransformer(String templateString,
                              Map<String, Object> baseImmutableBindings,
                              Function<Map<String, Object>, Map<String, Object>> wrapSourceAsContextConverter,
                              ResourceLocator resourceLocator)
    {
        jinjava = new Jinjava();
        this.wrapSourceAsContextConverter = wrapSourceAsContextConverter;
        jinjava.setResourceLocator(resourceLocator);
        threadLocalInterpreter = ThreadLocal.withInitial(() -> {
            var context = new Context(null, baseImmutableBindings);
            return jinjava.getGlobalConfig().getInterpreterFactory().newInstance(jinjava, context, jinjava.getGlobalConfig());
        });
        var interpreter = threadLocalInterpreter.get();
        this.template = interpreter.parse(templateString);
    }

    @SneakyThrows
    @Override
    public Map<String, Object> transformJson(Map<String, Object> incomingJson) {
        var sourceBindings = wrapSourceAsContextConverter.apply(incomingJson);
        JinjavaInterpreter parentInterpreter = threadLocalInterpreter.get();
        Context childContext = new Context(parentInterpreter.getContext(), sourceBindings);
        JinjavaInterpreter interpreter = new JinjavaInterpreter(jinjava, childContext, parentInterpreter.getConfig());
        String resultStr = interpreter.render(template);
        log.atInfo().setMessage("output = {}").addArgument(resultStr).log();
        return objectMapper.readValue(resultStr, Map.class);
    }
}
