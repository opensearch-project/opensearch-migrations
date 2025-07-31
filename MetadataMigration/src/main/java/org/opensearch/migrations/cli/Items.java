package org.opensearch.migrations.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.commands.JsonOutput;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.utils.JsonUtils;

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
    
    @Override
    public String asJsonOutput() {
        Map<String, Object> json = new HashMap<>();
        json.put("dryRun", dryRun);
        
        // Process successful and failed items
        json.put("indexTemplates", processItems(indexTemplates));
        json.put("componentTemplates", processItems(componentTemplates));
        json.put("indexes", processItems(indexes));
        json.put("aliases", processItems(aliases));
        
        if (failureMessage != null) {
            json.put("failureMessage", failureMessage);
        }
        
        json.put("errors", getAllErrors());
        
        return JsonUtils.toJson(json, "Items");
    }
    
    private List<Map<String, Object>> processItems(List<CreationResult> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (CreationResult item : items) {
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("name", item.getName());
            itemMap.put("successful", item.wasSuccessful());
            
            if (!item.wasSuccessful() && item.getFailureType() != null) {
                Map<String, Object> failure = new HashMap<>();
                failure.put("type", item.getFailureType().name());
                failure.put("message", item.getFailureType().getMessage());
                failure.put("fatal", item.getFailureType().isFatal());
                
                if (item.getFailureType().isFatal() && item.getException() != null) {
                    failure.put("exception", item.getException().getMessage() != null 
                        ? item.getException().getMessage() 
                        : item.getException().toString());
                }
                
                itemMap.put("failure", failure);
            }
            
            result.add(itemMap);
        }
        
        return result;
    }
}
