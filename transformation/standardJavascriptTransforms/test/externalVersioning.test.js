const main = require("../src/externalVersioning");

function indexOperation(version = 7) {
    return {
        schema: "rfs-opensearch-bulk-v1",
        operation_type: "index",
        operation: { _index: "products", _id: "product-1", routing: "tenant-1" },
        document: { "@version_number": version, title: "Original" }
    };
}

describe("External versioning", () => {
    test.each([undefined, null, {}])("defaults to the snapshot's internal version: %s", config => {
        const item = indexOperation(42);
        item.source_metadata = { _version: "9007199254740993", luceneDocNumber: 5 };
        main(config)(item);
        expect(item.operation.version).toBe("9007199254740993");
        expect(item.operation.version_type).toBe("external");
        expect(item.document["@version_number"]).toBe(42);
    });

    test("does not substitute a source field for a missing snapshot version", () => {
        expect(() => main()(indexOperation())).toThrow("Missing snapshot _version");
    });

    test("an explicit source field overrides the snapshot version", () => {
        const item = indexOperation(42);
        item.source_metadata = { _version: "7" };
        main({ versionField: "@version_number" })(item);
        expect(item.operation.version).toBe(42);
    });

    test("sets bulk metadata using the source field and preserves the document", () => {
        const item = indexOperation();
        const source = structuredClone(item.document);

        expect(main({ versionField: "@version_number" })(item)).toBe(item);
        expect(item.operation).toEqual({
            _index: "products", _id: "product-1", routing: "tenant-1",
            version: 7, version_type: "external"
        });
        expect(item.document).toEqual(source);
    });

    test("can use external_gte for equal-version overwrites", () => {
        const item = indexOperation();
        main({ versionField: "@version_number", versionType: "external_gte" })(item);
        expect(item.operation.version_type).toBe("external_gte");
    });

    test("uses an array of field names for a nested source path", () => {
        const item = indexOperation();
        item.document = { metadata: { "revision.number": 42 } };
        main({ versionField: ["metadata", "revision.number"] })(item);
        expect(item.operation.version).toBe(42);
    });

    test("treats a string field name containing dots as a literal key", () => {
        const item = indexOperation();
        item.document = { "revision.number": 9 };
        main({ versionField: "revision.number" })(item);
        expect(item.operation.version).toBe(9);
    });

    test("handles a batch without changing its order or delete operations", () => {
        const first = indexOperation(3);
        const second = indexOperation(4);
        second.operation._id = "product-2";
        const deletion = {
            schema: "rfs-opensearch-bulk-v1",
            operation_type: "delete",
            operation: { _index: "products", _id: "removed" }
        };
        const originalDelete = structuredClone(deletion);
        const result = main({ versionField: "@version_number" })([first, deletion, second]);

        expect(result).toEqual([first, originalDelete, second]);
        expect(result.map(item => item.operation.version)).toEqual([3, undefined, 4]);
    });

    test("supports Maps from the transformation runtime", () => {
        const operation = new Map([
            ["_index", "products"], ["_id", "product-1"],
            ["if_seq_no", 2], ["if_primary_term", 1]
        ]);
        const source = new Map([["metadata", new Map([["revision", "9223372036854775807"]])]]);
        const item = new Map([
            ["schema", "rfs-opensearch-bulk-v1"], ["operation_type", "index"],
            ["operation", operation], ["document", source]
        ]);
        const transform = main(new Map([["versionField", ["metadata", "revision"]]]));

        expect(transform(item)).toBe(item);
        expect(operation.get("version")).toBe("9223372036854775807");
        expect(operation.get("version_type")).toBe("external");
        expect(operation.has("if_seq_no")).toBe(false);
        expect(operation.has("if_primary_term")).toBe(false);
        expect(item.get("document")).toBe(source);
    });

    test("replaces internal versioning and removes sequence-number concurrency checks", () => {
        const item = indexOperation(11);
        Object.assign(item.operation, {
            version: 2, version_type: "internal", if_seq_no: 5, if_primary_term: 3
        });

        main({ versionField: "@version_number" })(item);

        expect(item.operation.version).toBe(11);
        expect(item.operation.version_type).toBe("external");
        expect(item.operation).not.toHaveProperty("if_seq_no");
        expect(item.operation).not.toHaveProperty("if_primary_term");
    });

    test.each([0, 1, Number.MAX_SAFE_INTEGER, "0", "9007199254740993", "9223372036854775807"])(
        "preserves a valid version without rounding: %s", version => {
            const item = indexOperation(version);
            main({ versionField: "@version_number" })(item);
            expect(item.operation.version).toBe(version);
        }
    );

    test.each([
        -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1,
        "", " ", "1.0", "1e3", "-1", "01", "9223372036854775808", "92233720368547758070",
        true, {}, [], 7n
    ])("rejects an invalid or imprecise version: %s", version => {
        const item = indexOperation(version);
        expect(() => main({ versionField: "@version_number" })(item))
            .toThrow("External version must be");
        expect(item.operation).not.toHaveProperty("version_type");
    });

    test.each([undefined, null])("rejects a missing version: %s", version => {
        const item = indexOperation();
        item.document["@version_number"] = version;
        expect(() => main({ versionField: "@version_number" })(item))
            .toThrow('Missing external version at _source path ["@version_number"] for document product-1');
    });

    test("rejects a missing nested field or document body", () => {
        const transform = main({ versionField: ["metadata", "revision"] });
        const item = indexOperation();
        expect(() => transform(item)).toThrow("Missing external version");
        delete item.document;
        expect(() => transform(item)).toThrow("Missing external version");
    });

    test.each([undefined, null, "", 3])("rejects a missing or invalid source ID: %s", id => {
        const item = indexOperation();
        item.operation._id = id;
        expect(() => main({ versionField: "@version_number" })(item))
            .toThrow("requires a source _id");
    });

    test("rejects create-only operations", () => {
        const item = indexOperation();
        item.operation.op_type = "create";
        expect(() => main({ versionField: "@version_number" })(item))
            .toThrow("op_type=create is not supported");
    });

    test.each([null, undefined, {}, { schema: "other" }, { operation_type: "index" }])(
        "passes unrelated input through: %s", input => {
            expect(main({ versionField: "@version_number" })(input)).toBe(input);
        }
    );

    test("accepts an empty batch", () => {
        expect(main({ versionField: "@version_number" })([])).toEqual([]);
    });

    test.each([{ versionField: "" }, { versionField: [] },
        { versionField: 7 }, { versionField: ["metadata", ""] }, { versionField: [null] }])(
        "rejects invalid configuration: %s", config => {
            expect(() => main(config)).toThrow("versionField must be");
        }
    );

    test.each(["internal", "force", "", 7])("rejects unsupported version type: %s", versionType => {
        expect(() => main({ versionField: "@version_number", versionType }))
            .toThrow('versionType must be "external" or "external_gte"');
    });
});
