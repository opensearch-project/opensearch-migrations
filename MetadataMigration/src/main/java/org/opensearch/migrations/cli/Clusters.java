package org.opensearch.migrations.cli;

import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.ClusterWriter;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Builder
public class Clusters {
    private ClusterReader source;
    private ClusterWriter target;

    public String asCliOutput() {
        var sb = new StringBuilder();
        sb.append("Clusters:" + System.lineSeparator());
        if (getSource() != null) {
            sb.append(Format.indentToLevel(1) + "Source:" + System.lineSeparator());
            sb.append(Format.indentToLevel(2) + getSource() + System.lineSeparator());
            sb.append(System.lineSeparator());
        }
        if (getTarget() != null) {
            sb.append(Format.indentToLevel(1) + "Target:" + System.lineSeparator());
            sb.append(Format.indentToLevel(2) + getTarget() + System.lineSeparator());
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
