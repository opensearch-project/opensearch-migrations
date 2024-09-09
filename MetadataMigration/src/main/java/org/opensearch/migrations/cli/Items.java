package org.opensearch.migrations.cli;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Builder;
import lombok.Data;

/**
 * Either items that are candidates for migration or have been migrated;
 */
@Builder
@Data
public class Items {
    public boolean dryRun;
    public List<String> indexTemplates;
    public List<String> componentTemplates;
    public List<String> indexes;
    public List<String> aliases;

    public String toString() {
        var sb = new StringBuilder();
        if (isDryRun()) {
            sb.append("Migration Candidates:" + System.lineSeparator());
        } else {
            sb.append("Migrated Items:" + System.lineSeparator());
        }
        sb.append("   Index Templates:" + System.lineSeparator());
        sb.append("      " + getPrintableList(getIndexTemplates()) + System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("   Component Templates:" + System.lineSeparator());
        sb.append("      " + getPrintableList(getComponentTemplates()) + System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("   Indexes:" + System.lineSeparator());
        sb.append("      " + getPrintableList(getIndexes()) + System.lineSeparator());
        sb.append(System.lineSeparator());
        sb.append("   Aliases:" + System.lineSeparator());
        sb.append("      " + getPrintableList(getAliases()) + System.lineSeparator());
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
