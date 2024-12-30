package org.opensearch.migrations.data.workloads;

import java.util.function.Supplier;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public enum Workloads {
    GEONAMES(Geonames::new),
    HTTP_LOGS(HttpLogs::new),
    NESTED(Nested::new),
    NYC_TAXIS(NycTaxis::new);

    @Getter
    private Supplier<Workload> newInstance;
}
