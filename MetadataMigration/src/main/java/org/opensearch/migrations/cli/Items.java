package org.opensearch.migrations.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.commands.JsonOutput;
import org.opensearch.migrations.metadata.CreationResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Either items that are candidates for migration or have been migrated;
 */
@Builder
@Data
@Slf4j
public class Items implements JsonOutput {
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

    public int getAlreadyExistsCount() {
        return (int) indexes.stream()
            .filter(r -> r.getFailureType() == CreationResult.CreationFailureType.ALREADY_EXISTS)
            .count();
    }

    public List<String> getAllErrors() {
        var errors = new ArrayList<String>();
        if (failureMessage != null) {
            errors.add(failureMessage);
        }

        Stream.of(indexTemplates, componentTemplates, indexes, aliases)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .filter(result -> result.getFailureType() != null && result.getFailureType().isFatal())
            .map(this::failureMessage)
            .forEach(errors::add);

        // Indexes that already exist on the target are treated as fatal — they block backfill
        // to prevent documents from being indexed against potentially stale mappings.
        // Templates/aliases with ALREADY_EXISTS remain non-fatal warnings.
        indexes.stream()
            .filter(r -> r.getFailureType() == CreationResult.CreationFailureType.ALREADY_EXISTS)
            .map(this::failureMessage)
            .forEach(errors::add);

        return errors;
    }

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

        int alreadyExistsCount = getAlreadyExistsCount();
        if (alreadyExistsCount > 0) {
            sb.append(System.lineSeparator());
            sb.append(alreadyExistsCount)
              .append(" index(es) already exist on the target cluster and may have stale mappings.")
              .append(System.lineSeparator());
            sb.append("To proceed, choose one of:").append(System.lineSeparator());
            sb.append(Format.indentToLevel(1))
              .append("1. Clear the target indices and metadata, then re-run from scratch.")
              .append(System.lineSeparator());
            sb.append(Format.indentToLevel(1))
              .append("2. Use --index-allowlist on the metadata step to migrate only the missing items.")
              .append(System.lineSeparator());
            sb.append(Format.indentToLevel(1))
              .append("3. Re-run with --allow-existing-indices to proceed despite existing data (may result in stale metadata on target).")
              .append(System.lineSeparator());
        }

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
            .sorted()
            .collect(Collectors.toList());

        if (!successfulItems.isEmpty()) {
            successfulItems.forEach(item -> {
                sb.append(Format.indentToLevel(2))
                .append("- ")
                .append(item)
                .append(System.lineSeparator());
            });
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
        boolean isIndexAlreadyExists = result.getFailureType() == CreationResult.CreationFailureType.ALREADY_EXISTS
            && indexes.contains(result);
        var sb = new StringBuilder()
            .append(result.getFailureType().isFatal() || isIndexAlreadyExists ? "ERROR" : "WARN")
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
    
    @Override
    public JsonNode asJsonOutput() {
        var root = JsonNodeFactory.instance.objectNode();

        root.put("dryRun", dryRun);

        int alreadyExistsCount = getAlreadyExistsCount();
        if (alreadyExistsCount > 0) {
            root.put("alreadyExistsCount", alreadyExistsCount);
        }

        buildArray("indexTemplates", indexTemplates, root);
        buildArray("componentTemplates", componentTemplates, root);
        buildArray("indexes",          indexes,          root);
        buildArray("aliases",          aliases,          root);

        if (failureMessage != null) {
            root.put("failureMessage", failureMessage);
        }

        var errorsNode = root.putArray("errors");
        for (var err : getAllErrors()) {
            errorsNode.add(err);
        }

        return root;
    }

    /**
     * Helper to convert a List<CreationResult> into a JSON array under `fieldName` on `parent`.
     */
    private void buildArray(String fieldName, List<CreationResult> items, ObjectNode parent) {
        var array = parent.putArray(fieldName);
        for (var item : items) {
            var obj = array.addObject();
            obj.put("name",       item.getName());
            obj.put("successful", item.wasSuccessful());

            if (!item.wasSuccessful() && item.getFailureType() != null) {
                var failure = obj.putObject("failure");
                var ft = item.getFailureType();
                failure.put("type",    ft.name());
                failure.put("message", ft.getMessage());
                failure.put("fatal",   ft.isFatal());

                if (ft.isFatal() && item.getException() != null) {
                    var exMsg = item.getException().getMessage();
                    failure.put("exception", exMsg != null ? exMsg : item.getException().toString());
                }
            }
        }
    }
}
