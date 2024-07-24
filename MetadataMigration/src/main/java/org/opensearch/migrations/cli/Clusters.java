package org.opensearch.migrations.cli;

import java.util.List;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Clusters {
    public Source source;
    public Target target;
    public List<Message> messages;

    public Clusters() {
    }

    public String print() {
        (new Printable().Builder()).level(0).build();

        final var sb = new StringBuilder("Clusters");
        return sb.toString();
    }

    @Builder
    public static class Printable {
        public int level;
        public String section;
        public List<String> entries;
    }
}
