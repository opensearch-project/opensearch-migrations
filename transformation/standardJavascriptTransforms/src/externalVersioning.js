/**
 * Configures versioning on RFS bulk operations. Internal mode removes explicit
 * versioning; external modes retain the action version or use a field in _source.
 * Snapshot versions are already preserved by default without this transformation.
 *
 * bindingsObject:
 *   versionType: "internal", "external" (default), or "external_gte"
 *   versionField: optional field name, or array of field names for a nested path;
 *                 used only by external modes
 *
 * By default, retains operation.version, falling back to source_metadata._version
 * if a previous transformation removed the action version.
 * External modes require source IDs (--server-generated-ids NEVER). Conflicts
 * follow normal failure handling. Use --allowed-doc-exception-types
 * version_conflict_engine_exception to explicitly treat them as success.
 */

function getField(object, key) {
    return object instanceof Map ? object.get(key) : object?.[key];
}

function setField(object, key, value) {
    if (object instanceof Map) {
        object.set(key, value);
    } else {
        object[key] = value;
    }
}

function deleteField(object, key) {
    if (object instanceof Map) {
        object.delete(key);
    } else if (object != null) {
        delete object[key];
    }
}

function validateVersion(version) {
    if (typeof version === "number" && Number.isSafeInteger(version) && version >= 0) {
        return;
    }
    // Keep decimal strings as strings: converting them to Number would round
    // versions above 2^53 - 1 before Jackson reads the bulk metadata as a Long.
    if (typeof version === "string" && version.length <= 19 && /^(0|[1-9]\d*)$/.test(version)
            && BigInt(version) <= 9223372036854775807n) {
        return;
    }
    throw new Error("External version must be a non-negative safe integer or a decimal string "
        + "between 0 and 9223372036854775807. Use a string for versions above 2^53 - 1.");
}

function resolveVersion(item, operation, path) {
    if (path != null) {
        return path.reduce((value, key) => getField(value, key), getField(item, "document"));
    }
    return getField(operation, "version") ?? getField(getField(item, "source_metadata"), "_version");
}

function main(context) {
    const versionType = getField(context, "versionType") ?? "external";
    if (!["internal", "external", "external_gte"].includes(versionType)) {
        throw new Error('versionType must be "internal", "external", or "external_gte".');
    }
    const versionField = getField(context, "versionField");
    const path = typeof versionField === "string" ? [versionField] : versionField;
    if (versionType !== "internal" && path != null && (!Array.isArray(path) || path.length === 0
            || !path.every(key => typeof key === "string" && key.length > 0))) {
        throw new Error("versionField must be a non-empty field name or an array of field names in _source.");
    }

    const transformOperation = (item) => {
        const operationType = getField(item, "operation_type");
        if (getField(item, "schema") !== "rfs-opensearch-bulk-v1"
                || (operationType !== "index" && operationType !== "delete")) {
            return item;
        }
        const operation = getField(item, "operation");
        if (versionType === "internal") {
            deleteField(operation, "version");
            deleteField(operation, "version_type");
            return item;
        }
        // A delta snapshot contains the prior document's version, not its
        // deletion version. External modes must not assign it to a delete.
        if (operationType === "delete") {
            return item;
        }
        const id = getField(operation, "_id");
        if (typeof id !== "string" || id.length === 0) {
            throw new Error("External versioning requires a source _id. Use --server-generated-ids NEVER.");
        }
        if (getField(operation, "op_type") === "create") {
            throw new Error("External versioning requires index operations; op_type=create is not supported.");
        }

        const version = resolveVersion(item, operation, path);
        if (version === undefined || version === null) {
            const location = path == null ? "action version or snapshot _version"
                : "external version at _source path " + JSON.stringify(path);
            throw new Error("Missing " + location
                + " for document " + id + ".");
        }
        validateVersion(version);
        setField(operation, "version", version);
        setField(operation, "version_type", versionType);
        // Sequence-number concurrency checks cannot be combined with external versioning.
        deleteField(operation, "if_seq_no");
        deleteField(operation, "if_primary_term");
        return item;
    };

    return (input) => Array.isArray(input) ? input.map(transformOperation) : transformOperation(input);
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = main;
}

(() => main)();
