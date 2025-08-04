package org.opensearch.migrations.cli;

import java.util.List;

import org.opensearch.migrations.bulkload.transformers.Transformer;
import org.opensearch.migrations.commands.JsonOutput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
    @SuppressWarnings("java:S1874") // False positive on overload of ObjectNode.put(...)
    public JsonNode asJsonOutput() {
        var root = JsonNodeFactory.instance.objectNode();
        var transformersArray = root.putArray("transformers");

        if (transformerInfos != null) {
            for (var info : transformerInfos) {
                var tNode = transformersArray.addObject();
                tNode.put("name", info.getName());

                var descArray = tNode.putArray("description");
                for (var line : info.getDescriptionLines()) {
                    descArray.add(line);
                }

                if (info.getUrl() != null) {
                    tNode.put("url", info.getUrl());
                }
            }
        }

        return root;
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
