package org.opensearch.migrations.data.workloads;

import java.util.function.Supplier;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public enum Workloads {
    Geonames(Geonames::new),
    HttpLogs(HttpLogs::new),
    Nested(Nested::new),
    NycTaxis(NycTaxis::new);

    @Getter
    private Supplier<Workload> newInstance;
}
