package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import lombok.NonNull;

public class JsonCompositePrecondition implements IJsonPrecondition {
    List<IJsonPrecondition> jsonPreconditionList;
    CompositeOperation operation;

    public enum CompositeOperation {
        ALL,
        ANY,
        NONE,
    }

    public JsonCompositePrecondition(@NonNull CompositeOperation operation,
        IJsonPrecondition... jsonPreconditions) {
        this.operation = operation;
        this.jsonPreconditionList = List.of(jsonPreconditions);
    }

    @Override
    public boolean evaluatePrecondition(Map<String, Object> incomingJson) {
        var preconditions = jsonPreconditionList.stream();
        Predicate<IJsonPrecondition> predicate = p -> p.evaluatePrecondition(incomingJson);
        switch (operation) {
            case ALL:
                return preconditions.allMatch(predicate);
            case ANY:
                return preconditions.anyMatch(predicate);
            case NONE:
                return preconditions.noneMatch(predicate);
            default:
                throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }
}
