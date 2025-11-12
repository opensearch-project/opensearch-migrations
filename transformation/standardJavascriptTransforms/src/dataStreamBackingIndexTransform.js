/**
 * Transforms data stream backing index operations for migration by replacing the
 * backing index name with the data stream name and setting op_type to "create".
 * 
 * Processes indexes starting with ".ds-" and extracts the data stream name using
 * regex matching on either format:
 * - .ds-<data-stream>-<yyyy.MM.dd>-<generation> (e.g., .ds-test-data-stream-2024.01.15-000034)
 * - .ds-<data-stream>-<generation> (e.g., .ds-test-data-stream-000034)
 */

function addVersionControlMetadata(document) {
    if (document && document.schema === 'rfs-opensearch-bulk-v1') {
        const indexName = document.operation?.["_index"];
        // Process data stream backing indexes (starting with ".ds-")
        if (indexName && indexName.startsWith('.ds-')) {
            // Extract data stream name from backing index pattern
            const dataStreamPattern = /^\.ds-(.+?)-(?:\d{4}\.\d{2}\.\d{2}-)?(\d{3,10})$/;
            const match = indexName.match(dataStreamPattern);
            
            if (match) {
                const dataStreamName = match[1];
                
                if (!document.operation) {
                    document.operation = {};
                }
                
                // Replace backing index name with data stream name
                document.operation["_index"] = dataStreamName;
                // Set operation type to create for data stream append
                document.operation.op_type = "create";
            }
        }
    }
    return document;
}

function main() {
    return (document) => {
        if (Array.isArray(document)) {
            return document.flat().map(item => addVersionControlMetadata(item));
        }
        return addVersionControlMetadata(document);
    };
}

// Visibility for testing
if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

// Entrypoint function
(() => main)();
