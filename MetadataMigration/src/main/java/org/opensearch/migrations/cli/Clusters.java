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
        return new Printable.Builder()
            .level(1)
            .section("Clusters")
            .build()
            .prettyPrint();
    }

    @Builder(builderClassName = "Builder")
    public static class Printable {
        private static final int LEVEL_SPACER_AMOUNT = 3; 
        private static final String SPACER = " "; 
        private static final String SECTION_ENDING = ":"; 

        public int level;
        public String section;
        public List<String> entries;

        public String prettyPrint() {
            var topIntentLevel = SPACER.repeat(level * LEVEL_SPACER_AMOUNT);
            var sb = new StringBuilder();
            sb.append(topIntentLevel + section + SECTION_ENDING + System.lineSeparator());

            var lowerIntentLevel = SPACER.repeat((level + 1) * LEVEL_SPACER_AMOUNT);
            entries.forEach(entry -> {
                sb.append(lowerIntentLevel + entry + System.lineSeparator());
            });


            return sb.toString();
        }
    }
}
