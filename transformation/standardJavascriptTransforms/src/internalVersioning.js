/**
 * Opts out of preserving snapshot versions on RFS bulk operations.
 * Removes the explicit version and version_type so the target uses internal
 * versioning. Source IDs, document bodies, and source_metadata are preserved.
 */

function getField(object, key) {
    return object instanceof Map ? object.get(key) : object?.[key];
}

function deleteField(object, key) {
    if (object instanceof Map) {
        object.delete(key);
    } else if (object != null) {
        delete object[key];
    }
}

function main() {
    const transformOperation = (item) => {
        if (getField(item, "schema") === "rfs-opensearch-bulk-v1"
                && ["index", "delete"].includes(getField(item, "operation_type"))) {
            const operation = getField(item, "operation");
            deleteField(operation, "version");
            deleteField(operation, "version_type");
        }
        return item;
    };

    return (input) => Array.isArray(input) ? input.map(transformOperation) : transformOperation(input);
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = main;
}

(() => main)();
