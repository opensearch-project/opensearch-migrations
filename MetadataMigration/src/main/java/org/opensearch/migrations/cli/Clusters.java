package org.opensearch.migrations.cli;

import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.cluster.ClusterWriter;
import org.opensearch.migrations.cluster.RemoteCluster;

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
            sb.append(Format.indentToLevel(2) + "Type: " + getSource().getTypeName() + " (" + getSource().getVersion() + ")" + System.lineSeparator());
            if (getSource() instanceof ClusterSnapshotReader) {
                var reader = (ClusterSnapshotReader) getSource();
                sb.append(Format.indentToLevel(2) + "Repository: " + reader.getSourceRepo().getRepoRootDir() + System.lineSeparator());
            }
            // TODO: Refactor into the readers themselves
            sb.append(System.lineSeparator());
        }
        if (getTarget() != null) {
            sb.append(Format.indentToLevel(1) + "Target:" + System.lineSeparator());
            sb.append(Format.indentToLevel(2) + "Type: " + getTarget().getTypeName() + " (" + getTarget().getVersion() + ")" + System.lineSeparator());
            if (getTarget() instanceof RemoteCluster) {
                var connection = ((RemoteCluster) getTarget()).getConnection();
                sb.append(Format.indentToLevel(2) + "URI: " + connection.getUri() + System.lineSeparator());
                sb.append(Format.indentToLevel(2) + "Protocol: " + connection.getProtocol() + System.lineSeparator());
                sb.append(Format.indentToLevel(2) + "TLS Verification: " + !connection.isInsecure() + System.lineSeparator());
                sb.append(Format.indentToLevel(2) + "Aws Auth: " + connection.isAwsSpecificAuthentication() + System.lineSeparator());
            }
            // TODO: Refactor into the writers themselves
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }
}
