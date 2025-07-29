package org.opensearch.migrations.cli;

import java.util.List;

import org.opensearch.migrations.bulkload.transformers.Transformer;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Builder
public class Transformers {
    @Singular
    private List<TransformerInfo> transformerInfos;
    private Transformer transformer;

    public String asCliOutput() {
        var sb = new StringBuilder();
        sb.append("Transformations:").append(System.lineSeparator());
        transformerInfos.forEach(transformer -> {
            sb.append(Format.indentToLevel(1))
                .append(transformer.name)
                .append(":")
                .append(System.lineSeparator());
            if (transformer.description != null) {
                sb.append(Format.indentToLevel(2))
                    .append(transformer.description)
                    .append(System.lineSeparator());
            }
            if (transformer.url != null) {
                sb.append(Format.indentToLevel(2))
                    .append("Learn more at ")
                    .append(transformer.url)
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

    @Builder
    @Value
    public static class TransformerInfo {
        public String name;
        public String description;
        public String url;
    }
}
