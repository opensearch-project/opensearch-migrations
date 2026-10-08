const main = require("../src/dataStreamBackingIndexTransform");

function operation(index) {
    return {
        schema: "rfs-opensearch-bulk-v1",
        operation_type: "index",
        operation: {
            _index: index, _id: "document-1",
            version: "9223372036854775807", version_type: "external"
        },
        document: { "@timestamp": "2024-01-15T00:00:00Z", message: "original" },
        source_metadata: { _version: "9223372036854775807" }
    };
}

describe("Data stream versioning compatibility", () => {
    test.each([".ds-events-000001", ".ds-events-2024.01.15-000001"])(
        "removes incompatible version metadata for create-only writes from %s", index => {
            const item = operation(index);
            const original = structuredClone(item);
            expect(main()(item)).toBe(item);
            expect(item.operation).toEqual({
                _index: "events", _id: "document-1", op_type: "create"
            });
            expect(item.document).toEqual(original.document);
            expect(item.source_metadata).toEqual(original.source_metadata);
        }
    );

    test.each(["products", ".ds-events-invalid"])("keeps versioning for unmatched index %s", index => {
        const item = operation(index);
        const original = structuredClone(item);
        expect(main()(item)).toEqual(original);
    });

    test("continues to support unversioned input", () => {
        const item = operation(".ds-events-000001");
        delete item.operation.version;
        delete item.operation.version_type;
        main()(item);
        expect(item.operation).toEqual({
            _index: "events", _id: "document-1", op_type: "create"
        });
    });

    test("only removes versions from data stream writes in a mixed batch", () => {
        const stream = operation(".ds-events-000001");
        const regular = operation("products");
        expect(main()([stream, regular])).toEqual([stream, regular]);
        expect(stream.operation).not.toHaveProperty("version");
        expect(stream.operation).not.toHaveProperty("version_type");
        expect(regular.operation.version).toBe("9223372036854775807");
        expect(regular.operation.version_type).toBe("external");
    });
});
