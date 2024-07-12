package com.rfs.common;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public class FilterScheme {
    private FilterScheme() {}

    public static Predicate<SnapshotRepo.Index> filterIndicesByAllowList(
        List<String> indexAllowlist,
        BiConsumer<String, Boolean> indexNameAcceptanceObserver
    ) {
        return index -> {
            boolean accepted;
            if (indexAllowlist.isEmpty()) {
                accepted = !index.getName().startsWith(".");
            } else {
                accepted = indexAllowlist.contains(index.getName());
            }

            indexNameAcceptanceObserver.accept(index.getName(), accepted);

            return accepted;
        };
    }
}
