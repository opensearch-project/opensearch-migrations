package org.opensearch.migrations.commands;

import org.opensearch.migrations.bulkload.common.RepositoryAccessCheckResult;
import org.opensearch.migrations.cli.Format;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public record RepositoryCheckResult(RepositoryAccessCheckResult check) implements Result {
    @Override
    public int getExitCode() {
        return check.isSuccessful() ? 0 : 1;
    }

    @Override
    public String getErrorMessage() {
        return check.isSuccessful() ? null : check.summary();
    }

    @Override
    public String asCliOutput() {
        var output = new StringBuilder();
        output.append("Repository access: ")
            .append(displayStatus(check.status()))
            .append(System.lineSeparator());
        output.append(Format.indentToLevel(1))
            .append(check.summary())
            .append(System.lineSeparator());
        for (var stage : check.stages()) {
            output.append(Format.indentToLevel(1))
                .append(stage.label())
                .append(": ")
                .append(displayStatus(stage.status()))
                .append(System.lineSeparator());
            output.append(Format.indentToLevel(2))
                .append(stage.message())
                .append(System.lineSeparator());
        }
        return output.toString();
    }

    @Override
    public JsonNode asJsonOutput() {
        var root = JsonNodeFactory.instance.objectNode();
        root.put("status", jsonStatus(check.status()));
        root.put("provider", check.provider());
        root.put("location", check.location());
        root.put("summary", check.summary());
        root.put("errorCode", getExitCode());
        var stages = root.putArray("stages");
        for (var stage : check.stages()) {
            var stageNode = stages.addObject();
            stageNode.put("id", stage.id());
            stageNode.put("label", stage.label());
            stageNode.put("status", jsonStatus(stage.status()));
            stageNode.put("message", stage.message());
        }
        return root;
    }

    private static String displayStatus(Enum<?> status) {
        return switch (status.name()) {
            case "VALID", "PASSED" -> "Valid";
            case "PARTIALLY_VERIFIED" -> "Partially verified";
            case "FAILED" -> "Failed";
            case "SKIPPED" -> "Skipped";
            default -> status.name();
        };
    }

    private static String jsonStatus(Enum<?> status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }
}
