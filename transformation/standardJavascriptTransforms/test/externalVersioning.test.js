const main = require("../src/externalVersioning");

function indexOperation() {
    return {
        schema: "rfs-opensearch-bulk-v1",
        operation_type: "index",
        operation: { _index: "products", _id: "d1", routing: "tenant", version: "7", version_type: "external" },
        source_metadata: { _version: "7" },
        document: { ext_version: 42, title: "original" }
    };
}

test.each(["internal", "external", "external_gte"])("applies %s to a mixed batch", versionType => {
    const item = indexOperation();
    const original = structuredClone(item);
    const deletion = {
        schema: item.schema, operation_type: "delete",
        operation: { _id: "removed" }, source_metadata: { _version: "11" }
    };
    const originalDelete = structuredClone(deletion);
    const unrelated = { schema: "other" };
    const result = main({ versionType })([item, deletion, unrelated]);

    expect(result).toEqual([item, originalDelete, unrelated]);
    expect(item.operation).toEqual(versionType === "internal"
        ? { _index: "products", _id: "d1", routing: "tenant" }
        : { ...original.operation, version_type: versionType });
    expect(item.document).toEqual(original.document);
    expect(item.source_metadata).toEqual(original.source_metadata);
});

test("uses an explicit field, then the action version, then the original snapshot version", () => {
    const item = indexOperation();
    item.operation.version = "9";
    main()(item);
    expect(item.operation.version).toBe("9");
    main({ versionField: "ext_version" })(item);
    expect(item.operation.version).toBe(42);
    main({ versionType: "internal" })(item);
    main()(item);
    expect(item.operation.version).toBe("7");
});

test("supports nested Map fields and replaces sequence-number concurrency checks", () => {
    const operation = new Map([["_id", "d1"], ["if_seq_no", 2], ["if_primary_term", 1]]);
    const source = new Map([["metadata", new Map([["revision.number", "9223372036854775807"]])]]);
    const item = new Map([
        ["schema", "rfs-opensearch-bulk-v1"], ["operation_type", "index"],
        ["operation", operation], ["document", source]
    ]);
    main(new Map([["versionType", "external_gte"], ["versionField", ["metadata", "revision.number"]]]))(item);
    expect(Object.fromEntries(operation)).toEqual({
        _id: "d1", version: "9223372036854775807", version_type: "external_gte"
    });
    expect(item.get("document")).toBe(source);
});

test("treats dots in a string field name literally", () => {
    const item = indexOperation();
    item.document = { "revision.number": 9 };
    main({ versionField: "revision.number" })(item);
    expect(item.operation.version).toBe(9);
});

test.each([0, "0", Number.MAX_SAFE_INTEGER, "9007199254740993", "9223372036854775807"])(
    "preserves a valid application version: %s", version => {
        const item = indexOperation();
        item.document.ext_version = version;
        main({ versionField: "ext_version" })(item);
        expect(item.operation.version).toBe(version);
    }
);

test.each([undefined, -1, 1.5, "1e3", Number.MAX_SAFE_INTEGER + 1,
    "9223372036854775808", "92233720368547758070"])(
    "rejects an invalid application version instead of falling back to the snapshot: %s", version => {
        const item = indexOperation();
        item.document.ext_version = version;
        expect(() => main({ versionField: "ext_version" })(item)).toThrow();
    }
);

test("rejects a missing version when neither action nor snapshot metadata supplies it", () => {
    const item = indexOperation();
    delete item.operation.version;
    delete item.source_metadata;
    expect(() => main()(item)).toThrow("Missing");
});

test.each([{ _id: "" }, { op_type: "create" }])("rejects incompatible external writes: %s", metadata => {
    const item = indexOperation();
    Object.assign(item.operation, metadata);
    expect(() => main()(item)).toThrow();
});

test.each([{ versionType: "unknown" }, { versionField: [] }])("rejects invalid configuration: %s", config => {
    expect(() => main(config)).toThrow();
});

test("internal mode ignores versionField and preserves explicit concurrency checks", () => {
    const item = indexOperation();
    Object.assign(item.operation, { if_seq_no: 5, if_primary_term: 3 });
    main({ versionType: "internal", versionField: [] })(item);
    expect(item.operation).toEqual({
        _index: "products", _id: "d1", routing: "tenant", if_seq_no: 5, if_primary_term: 3
    });
});
