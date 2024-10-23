package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import lombok.NonNull;

public class JsonCompositePredicate implements IJsonPredicate {
    List<IJsonPredicate> jsonPredicateList;
    CompositeOperation operation;

    public enum CompositeOperation {
        ALL,
        ANY,
        NONE,
    }

    public JsonCompositePredicate(@NonNull CompositeOperation operation,
        IJsonPredicate... jsonPredicates) {
        this.operation = operation;
        this.jsonPredicateList = List.of(jsonPredicates);
    }

    @Override
    public boolean evaluatePredicate(Map<String, Object> incomingJson) {
        var Predicates = jsonPredicateList.stream();
        Predicate<IJsonPredicate> predicate = p -> p.evaluatePredicate(incomingJson);
        switch (operation) {
            case ALL:
                return Predicates.allMatch(predicate);
            case ANY:
                return Predicates.anyMatch(predicate);
            case NONE:
                return Predicates.noneMatch(predicate);
            default:
                throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }
}
