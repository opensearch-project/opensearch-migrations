const main = require("../src/internalVersioning");
const externalVersioning = require("../src/externalVersioning");

function indexOperation() {
    return {
        schema: "rfs-opensearch-bulk-v1",
        operation_type: "index",
        operation: {
            _index: "products", _id: "product-1", routing: "tenant-1",
            version: "9223372036854775807", version_type: "external"
        },
        document: { ext_version: 42, version: "application value" },
        source_metadata: { _version: "9223372036854775807", luceneDocNumber: 5 }
    };
}

describe("Internal versioning", () => {
    test.each(["external", "external_gte", "internal"])(
        "removes explicit %s versioning without changing the ID, body, or source metadata", versionType => {
            const item = indexOperation();
            item.operation.version_type = versionType;
            const original = structuredClone(item);

            expect(main()(item)).toBe(item);
            expect(item.operation).toEqual({
                _index: "products", _id: "product-1", routing: "tenant-1"
            });
            expect(item.document).toEqual(original.document);
            expect(item.source_metadata).toEqual(original.source_metadata);
        }
    );

    test("preserves explicit sequence-number concurrency checks", () => {
        const item = indexOperation();
        Object.assign(item.operation, { if_seq_no: 5, if_primary_term: 3 });
        main()(item);
        expect(item.operation.if_seq_no).toBe(5);
        expect(item.operation.if_primary_term).toBe(3);
    });

    test("handles a batch containing index and delete operations", () => {
        const index = indexOperation();
        const deletion = {
            schema: "rfs-opensearch-bulk-v1",
            operation_type: "delete",
            operation: { _index: "products", _id: "removed", version: "7", version_type: "external" }
        };
        const result = main()([index, deletion]);
        expect(result).toEqual([index, deletion]);
        for (const item of result) {
            expect(item.operation).not.toHaveProperty("version");
            expect(item.operation).not.toHaveProperty("version_type");
        }
        expect(deletion.operation._id).toBe("removed");
    });

    test("supports Maps from the transformation runtime", () => {
        const operation = new Map([
            ["_id", "product-1"], ["version", "7"], ["version_type", "external"]
        ]);
        const item = new Map([
            ["schema", "rfs-opensearch-bulk-v1"], ["operation_type", "index"], ["operation", operation]
        ]);
        expect(main()(item)).toBe(item);
        expect(operation.get("_id")).toBe("product-1");
        expect(operation.has("version")).toBe(false);
        expect(operation.has("version_type")).toBe(false);
    });

    test("retains the snapshot version so a later transformation can opt back in", () => {
        const item = indexOperation();
        main()(item);
        externalVersioning({ versionType: "external_gte" })(item);
        expect(item.operation.version).toBe("9223372036854775807");
        expect(item.operation.version_type).toBe("external_gte");
    });

    test("is idempotent for operations already using default internal versioning", () => {
        const item = indexOperation();
        delete item.operation.version;
        delete item.operation.version_type;
        const original = structuredClone(item);
        main()(item);
        main()(item);
        expect(item).toEqual(original);
    });

    test.each([null, undefined, {}, { schema: "other" }, {
        schema: "other", operation_type: "index", operation: { version: 5, version_type: "external" }
    }])("passes unrelated input through: %s", input => {
        const original = structuredClone(input);
        expect(main()(input)).toEqual(original);
    });

    test("accepts an empty batch", () => {
        expect(main()([])).toEqual([]);
    });
});
