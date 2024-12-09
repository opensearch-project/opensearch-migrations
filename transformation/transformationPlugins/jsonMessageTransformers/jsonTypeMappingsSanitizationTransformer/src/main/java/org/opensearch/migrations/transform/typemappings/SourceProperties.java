package org.opensearch.migrations.transform.typemappings;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceProperties {
    private String type;
    private Version version;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Version {
        private int major;
        private int minor;
    }
}
