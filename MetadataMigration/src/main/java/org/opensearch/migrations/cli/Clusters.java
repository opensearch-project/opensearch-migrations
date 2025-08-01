package org.opensearch.migrations.cli;

import org.opensearch.migrations.bulkload.common.FileSystemRepo;
import org.opensearch.migrations.bulkload.common.S3Repo;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.cluster.ClusterReader;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;
import org.opensearch.migrations.cluster.ClusterWriter;
import org.opensearch.migrations.cluster.RemoteCluster;
import org.opensearch.migrations.commands.JsonOutput;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@Builder
public class Clusters implements JsonOutput {
    private ClusterReader source;
    private ClusterWriter target;

    public String asCliOutput() {
        var sb = new StringBuilder();
        sb.append("Clusters:" + System.lineSeparator());
        if (getSource() != null) {
            sb.append(Format.indentToLevel(1) + "Source:" + System.lineSeparator());
            sb.append(Format.indentToLevel(2) + "Type: " + getSource().getFriendlyTypeName() + " (" + getSource().getVersion() + ")" + System.lineSeparator());
            additionalSourceDetails(sb);
            sb.append(System.lineSeparator());
        }
        if (getTarget() != null) {
            sb.append(Format.indentToLevel(1) + "Target:" + System.lineSeparator());
            sb.append(Format.indentToLevel(2) + "Type: " + getTarget().getFriendlyTypeName() + " (" + getTarget().getVersion() + ")" + System.lineSeparator());
            additionalTargetDetails(sb);
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    private void additionalSourceDetails(StringBuilder sb) {
        if (getSource() instanceof ClusterSnapshotReader) {
            var reader = (ClusterSnapshotReader) getSource();
            var sourceRepo = reader.getSourceRepo();
            if (sourceRepo instanceof S3Repo) {
                var s3Repo = (S3Repo)sourceRepo;
                sb.append(Format.indentToLevel(2) + "S3 repository: " + s3Repo.getS3RepoUri().uri + System.lineSeparator());
            }
            if (sourceRepo instanceof FileSystemRepo) {
                sb.append(Format.indentToLevel(2) + "Local repository: " + sourceRepo.getRepoRootDir() + System.lineSeparator());
            }
        }

        if (getSource() instanceof RemoteCluster) {
            var remoteCluster = (RemoteCluster) getSource();
            connectionContextDetails(sb, remoteCluster.getConnection());
        }
    }

    private void additionalTargetDetails(StringBuilder sb) {
        if (getTarget() instanceof RemoteCluster) {
            var remoteCluster = (RemoteCluster) getTarget();
            connectionContextDetails(sb, remoteCluster.getConnection());
        }
    }

    private void connectionContextDetails(StringBuilder sb, ConnectionContext connection) {
        connection.toUserFacingData().forEach((key, value) -> {
            sb.append(Format.indentToLevel(2) + key + ": " + value + System.lineSeparator());
        });
    }
    
    @Override
    public JsonNode asJsonOutput() {
        var root = JsonNodeFactory.instance.objectNode();

        var mapper = new ObjectMapper();
        if (source != null) {
            var src = root.putObject("source");
            src.put("type", source.getFriendlyTypeName());
            src.put("version", source.getVersion().toString());

            if (source instanceof ClusterSnapshotReader) {
                var reader = (ClusterSnapshotReader) source;
                var sourceRepo = reader.getSourceRepo();
                if (sourceRepo instanceof S3Repo) {
                    var s3Repo = (S3Repo) sourceRepo;
                    src.put("s3Repository", s3Repo.getS3RepoUri().uri);
                } else if (sourceRepo instanceof FileSystemRepo) {
                    src.put("localRepository", sourceRepo.getRepoRootDir().toString());
                }
            }

            if (source instanceof RemoteCluster) {
                var connection = ((RemoteCluster) source).getConnection();
                var connNode = mapper.valueToTree(connection);
                connNode.properties().forEach(entry -> src.set(entry.getKey(), entry.getValue()));
            }
        }

        if (target != null) {
            var tgt = root.putObject("target");
            tgt.put("type", target.getFriendlyTypeName());
            tgt.put("version", target.getVersion().toString());

            if (target instanceof RemoteCluster) {
                var connection = ((RemoteCluster) target).getConnection();
                var connNode = mapper.valueToTree(connection);
                connNode.properties().forEach(entry -> tgt.set(entry.getKey(), entry.getValue()));
            }
        }

        return root;
    }
}
