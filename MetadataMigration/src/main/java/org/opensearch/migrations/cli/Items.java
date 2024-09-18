package org.opensearch.migrations.cli;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

/**
 * Either items that are candidates for migration or have been migrated;
 */
@Builder
@Data
public class Items {
    private final boolean dryRun;
    @NonNull
    private final List<String> indexTemplates;
    @NonNull
    private final List<String> componentTemplates;
    @NonNull
    private final List<String> indexes;
    @NonNull
    private final List<String> aliases;

    public String asCliOutput() {
        var sb = new StringBuilder();
        if (isDryRun()) {
            sb.append("Migration Candidates:" + System.lineSeparator());
        } else {
            sb.append("Migrated Items:" + System.lineSeparator());
        }
        sb.append(Format.indentToLevel(1) + "Index Templates:" + System.lineSeparator());
        sb.append(Format.indentToLevel(2) + getPrintableList(getIndexTemplates()) + System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append(Format.indentToLevel(1) + "Component Templates:" + System.lineSeparator());
        sb.append(Format.indentToLevel(2) +getPrintableList(getComponentTemplates()) + System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append(Format.indentToLevel(1) + System.lineSeparator());
        sb.append(Format.indentToLevel(2) + getPrintableList(getIndexes()) + System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append(Format.indentToLevel(1) + "Aliases:" + System.lineSeparator());
        sb.append(Format.indentToLevel(2) +getPrintableList(getAliases()) + System.lineSeparator());
        sb.append(System.lineSeparator());
        return sb.toString();
    }

    private String getPrintableList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "<NONE FOUND>";
        }
        return list.stream().sorted().collect(Collectors.joining(", "));
    }
}
