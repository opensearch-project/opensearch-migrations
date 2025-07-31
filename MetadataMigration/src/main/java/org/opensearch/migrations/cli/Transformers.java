package org.opensearch.migrations.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.commands.JsonOutput;
import org.opensearch.migrations.utils.JsonUtils;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Builder
public class Transformers implements JsonOutput {
    @Singular
    private List<TransformerInfo> transformerInfos;
    private Transformer transformer;

    public String asCliOutput() {
        var sb = new StringBuilder();
        sb.append("Transformations:").append(System.lineSeparator());
        transformerInfos.forEach(transform -> {
            sb.append(Format.indentToLevel(1))
                .append(transform.name)
                .append(":")
                .append(System.lineSeparator());
            transform.descriptionLines.forEach(line -> {
                sb.append(Format.indentToLevel(2)).append(line).append(System.lineSeparator());
            });
            if (transform.url != null) {
                sb.append(Format.indentToLevel(2))
                    .append("Learn more at ")
                    .append(transform.url)
                    .append(System.lineSeparator());
            }
        });
        if (transformerInfos.isEmpty()) {
            sb.append(Format.indentToLevel(1))
                .append("<None Found>")
                .append(System.lineSeparator());
        }
        return sb.toString();
    }
    
    @Override
    public String asJsonOutput() {
        Map<String, Object> json = new HashMap<>();
        List<Map<String, Object>> transformersList = new ArrayList<>();
        
        if (transformerInfos != null) {
            for (TransformerInfo info : transformerInfos) {
                Map<String, Object> transformerMap = new HashMap<>();
                transformerMap.put("name", info.getName());
                transformerMap.put("description", info.getDescriptionLines());
                if (info.getUrl() != null) {
                    transformerMap.put("url", info.getUrl());
                }
                transformersList.add(transformerMap);
            }
        }
        
        json.put("transformers", transformersList);
        
        return JsonUtils.toJson(json, "Transformers");
    }

    @Builder
    @Value
    public static class TransformerInfo {
        String name;
        @Singular
        List<String> descriptionLines;
        String url;
    }
}
