package org.opensearch.migrations.cli;

import java.util.List;
import java.util.stream.Collectors;

import org.opensearch.migrations.metadata.CreationResult;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;

/**
 * Either items that are candidates for migration or have been migrated;
 */
@Builder
@Data
public class Items {
    static final String NONE_FOUND_MARKER = "<NONE FOUND>";
    private final boolean dryRun;
    @NonNull
    private final List<CreationResult> indexTemplates;
    @NonNull
    private final List<CreationResult> componentTemplates;
    @NonNull
    private final List<CreationResult> indexes;
    @NonNull
    private final List<CreationResult> aliases;
    private final String failureMessage;

    public String asCliOutput() {
        var sb = new StringBuilder();
        if (isDryRun()) {
            sb.append("Migration Candidates:" + System.lineSeparator());
        } else {
            sb.append("Migrated Items:" + System.lineSeparator());
        }
        appendSection(sb, "Index Templates", getIndexTemplates());
        appendSection(sb, "Component Templates", getComponentTemplates());
        appendSection(sb, "Indexes", getIndexes());
        appendSection(sb, "Aliases", getAliases());

        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String sectionTitle, List<CreationResult> items) {
        sb.append(Format.indentToLevel(1))
          .append(sectionTitle)
          .append(":")
          .append(System.lineSeparator());

        if (items.isEmpty()) {
            sb.append(Format.indentToLevel(2))
                .append(NONE_FOUND_MARKER)
                .append(System.lineSeparator());
          } else {
            appendItems(sb, items);
            appendFailures(sb, items);
        }
        sb.append(System.lineSeparator());
    }

    private void appendItems(StringBuilder sb, List<CreationResult> items) {
        var successfulItems = items.stream()
            .filter(r -> r.wasSuccessful())
            .map(r -> r.getName())
            .collect(Collectors.toList());

        if (!successfulItems.isEmpty()) {
            sb.append(Format.indentToLevel(2))
                .append(getPrintableList(successfulItems))
                .append(System.lineSeparator());
        }
    }

    private void appendFailures(StringBuilder sb, List<CreationResult> items) {
        var failures = items.stream()
            .filter(r -> !r.wasSuccessful())
            .map(this::failureMessage)
            .sorted()
            .collect(Collectors.toList());
        if (!failures.isEmpty()) {
            failures.forEach(failure -> sb.append(Format.indentToLevel(2))
                                         .append(failure)
                                         .append(System.lineSeparator()));
        }
    }

    private String failureMessage(CreationResult result) {
        if (result.getFailureType() == null) {
            return "";
        }
        var sb = new StringBuilder()
            .append(result.getFailureType().isFatal() ? "ERROR" : "WARN")
            .append(" - ")
            .append(result.getName())
            .append(" ")
            .append(result.getFailureType().getMessage());

        if (result.getFailureType().isFatal() && result.getException() != null) {
            // There might not be an message in the exception, if so fallback to the toString of the exception.  
            var exceptionDetail = result.getException().getMessage() != null
                ? result.getException().getMessage()
                : result.getException().toString();
            sb.append(": " + exceptionDetail);
        }

        return sb.toString();
    }

    private String getPrintableList(List<String> list) {
        return list.stream().sorted().collect(Collectors.joining(", "));
    }
}
