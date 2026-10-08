package org.opensearch.migrations.bulkload.transformers;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.enums.IndexOpType;
import org.opensearch.migrations.bulkload.common.bulk.enums.VersionType;
import org.opensearch.migrations.bulkload.common.bulk.metadata.VersionControlMetadata;
import org.opensearch.migrations.bulkload.common.bulk.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.IJsonTransformerProvider;

/** Changes bulk versioning policy without parsing document bodies unless a version field is selected. */
public class BulkVersioningTransformerProvider implements IJsonTransformerProvider {
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        if (jsonConfig != null && !(jsonConfig instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Versioning configuration must be an object");
        }
        Map<?, ?> config = jsonConfig == null ? Map.of() : (Map<?, ?>) jsonConfig;
        Object configuredType = config.get("versionType");
        var versionType = VersionType.from(configuredType == null ? "external" : configuredType.toString());
        if (versionType == VersionType.FORCE) {
            throw new IllegalArgumentException("versionType must be internal, external, or external_gte");
        }
        Object field = config.get("versionField");
        List<?> path = field instanceof String ? List.of(field) : field instanceof List<?> list ? list : null;
        if (versionType != VersionType.INTERNAL && field != null
            && (path == null || path.isEmpty()
                || path.stream().anyMatch(key -> !(key instanceof String name) || name.isEmpty()))) {
            throw new IllegalArgumentException("versionField must be a non-empty field name or list of field names");
        }
        return new VersioningTransformer(versionType, versionType == VersionType.INTERNAL || path == null ? null : List.copyOf(path));
    }

    private static final class VersioningTransformer extends BulkOperationTransformer {
        private final VersionType versionType;
        private final List<?> versionPath;

        private VersioningTransformer(VersionType versionType, List<?> versionPath) {
            this.versionType = versionType;
            this.versionPath = versionPath;
        }

        @Override
        public List<BulkOperationSpec> transformOperations(List<BulkOperationSpec> operations) {
            for (var operation : operations) {
                if (versionType == VersionType.INTERNAL) {
                    var versioning = switch (operation.getOperation()) {
                        case IndexOperationMeta index -> index.getVersioning();
                        case DeleteOperationMeta delete -> delete.getVersioning();
                    };
                    if (versioning != null) {
                        versioning.setVersion(null);
                        versioning.setVersionType(null);
                    }
                } else if (operation instanceof IndexOp index) {
                    var metadata = index.getOperation();
                    if (metadata.getId() == null || metadata.getId().isEmpty() || metadata.getOpType() == IndexOpType.CREATE) {
                        throw new IllegalArgumentException("External versioning requires an index operation with a source _id");
                    }
                    var versioning = metadata.getVersioning();
                    Object version;
                    if (versionPath != null) {
                        version = operation.getDocument();
                        for (Object key : versionPath) {
                            version = version instanceof Map<?, ?> fields ? fields.get(key) : null;
                        }
                    } else {
                        version = versioning != null ? versioning.getVersion() : null;
                        if (version == null && operation.getSourceMetadata() != null) {
                            version = operation.getSourceMetadata().get(Document.SOURCE_META_VERSION);
                        }
                    }
                    Long numericVersion = parseVersion(version);
                    if (versioning == null) {
                        versioning = new VersionControlMetadata();
                        metadata.setVersioning(versioning);
                    }
                    versioning.setVersion(numericVersion);
                    versioning.setVersionType(versionType);
                    versioning.setIfSeqNo(null);
                    versioning.setIfPrimaryTerm(null);
                }
            }
            return operations;
        }

        private static Long parseVersion(Object version) {
            if (version instanceof Long value && value >= 0) {
                return value;
            }
            if (version instanceof Byte || version instanceof Short || version instanceof Integer) {
                long value = ((Number) version).longValue();
                if (value >= 0) {
                    return value;
                }
            } else if (version instanceof String text && !text.isEmpty() && text.length() <= 19) {
                try {
                    long value = Long.parseLong(text);
                    if (value >= 0 && Long.toString(value).equals(text)) {
                        return value;
                    }
                } catch (NumberFormatException ignored) {
                    // Report the same bounded-integer requirement for malformed and overflowing values.
                }
            }
            throw new IllegalArgumentException("External version must be an integer or decimal string between 0 and "
                + Long.MAX_VALUE);
        }
    }
}
