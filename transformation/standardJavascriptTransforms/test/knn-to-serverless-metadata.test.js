const main = require("../src/knn-to-serverless-metadata");

const transformMapping = main(null);

/**
 * Helper to create a knn_vector field definition
 */
const knnField = (dimension, methodConfig = {}) => {
    const field = new Map([
        ["type", "knn_vector"],
        ["dimension", dimension]
    ]);
    if (Object.keys(methodConfig).length > 0) {
        field.set("method", new Map(Object.entries(methodConfig)));
    }
    return field;
};

/**
 * Helper to create a method config Map
 */
const methodConfig = (name, engine, spaceType, params = {}) => {
    const method = {};
    if (name) method.name = name;
    if (engine) method.engine = engine;
    if (spaceType) method.space_type = spaceType;
    if (Object.keys(params).length > 0) {
        method.parameters = params;
    }
    return method;
};

/**
 * Wrap field mappings inside the required structure:
 * { body: { mappings: { properties: { <fields> } } } }
 */
const wrapMappings = (fieldsMap) =>
    new Map([
        ["body", new Map([
            ["mappings", new Map([
                ["properties", fieldsMap]
            ])]
        ])]
    ]);

/**
 * Wrap with plain objects instead of Maps (to test both code paths)
 */
const wrapMappingsPlainObject = (fields) => ({
    body: {
        mappings: {
            properties: fields
        }
    }
});

const unwrapField = (outMap, fieldName) =>
    outMap.get("body")
        .get("mappings")
        .get("properties")
        .get(fieldName);

const unwrapFieldPlain = (out, fieldName) =>
    out.body.mappings.properties[fieldName];

/* ---------- Test cases ---------- */

describe("knn-to-serverless-metadata transformer", () => {
    describe("Lucene engine conversion", () => {
        test("converts Lucene HNSW to Faiss HNSW", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(128, methodConfig("hnsw", "lucene", "l2", { m: 24, ef_construction: 200 }))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.type).toBe("knn_vector");
            expect(field.dimension).toBe(128);
            expect(field.method.name).toBe("hnsw");
            expect(field.method.engine).toBe("faiss");
            expect(field.method.space_type).toBe("l2");
            expect(field.method.parameters.m).toBe(24);
            expect(field.method.parameters.ef_construction).toBe(200);
        });

        test("preserves innerproduct space_type", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(64, methodConfig("hnsw", "lucene", "innerproduct", { m: 16, ef_construction: 100 }))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.engine).toBe("faiss");
            expect(field.method.space_type).toBe("innerproduct");
        });

        test("preserves cosinesimil space_type", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(64, methodConfig("hnsw", "lucene", "cosinesimil", {}))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.engine).toBe("faiss");
            expect(field.method.space_type).toBe("cosinesimil");
        });
    });

    describe("NMSLIB engine conversion", () => {
        test("converts NMSLIB to Faiss", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(256, methodConfig("hnsw", "nmslib", "l2", { m: 32, ef_construction: 150 }))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.engine).toBe("faiss");
            expect(field.method.name).toBe("hnsw");
            expect(field.method.parameters.m).toBe(32);
            expect(field.method.parameters.ef_construction).toBe(150);
        });
    });

    describe("Faiss engine preservation", () => {
        test("preserves Faiss HNSW without changes", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(128, methodConfig("hnsw", "faiss", "l2", { m: 16, ef_construction: 100 }))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            // Field should pass through unchanged since it's already Faiss HNSW
            expect(field instanceof Map).toBe(true);
            expect(field.get("type")).toBe("knn_vector");
            expect(field.get("method").get("engine")).toBe("faiss");
        });
    });

    describe("IVF method conversion", () => {
        test("converts IVF to HNSW", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(128, methodConfig("ivf", "faiss", "l2", { nlist: 4, nprobes: 2 }))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.name).toBe("hnsw");
            expect(field.method.engine).toBe("faiss");
            // IVF params should be replaced with HNSW defaults
            expect(field.method.parameters.m).toBe(16);
            expect(field.method.parameters.ef_construction).toBe(100);
            expect(field.method.parameters.nlist).toBeUndefined();
            expect(field.method.parameters.nprobes).toBeUndefined();
        });
    });

    describe("Space type conversion", () => {
        test("converts l1 to l2 with warning", () => {
            const consoleSpy = jest.spyOn(console, 'error').mockImplementation();
            
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(64, methodConfig("hnsw", "lucene", "l1", {}))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.space_type).toBe("l2");
            expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining("l1"));
            
            consoleSpy.mockRestore();
        });

        test("converts linf to l2 with warning", () => {
            const consoleSpy = jest.spyOn(console, 'error').mockImplementation();
            
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(64, methodConfig("hnsw", "lucene", "linf", {}))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.space_type).toBe("l2");
            expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining("linf"));
            
            consoleSpy.mockRestore();
        });
    });

    describe("Encoder handling", () => {
        test("converts Lucene SQ encoder to Faiss format", () => {
            const params = { 
                m: 16, 
                ef_construction: 100,
                encoder: { name: "sq" }
            };
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(128, methodConfig("hnsw", "lucene", "l2", params))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.parameters.encoder.name).toBe("sq");
            expect(field.method.parameters.encoder.parameters.type).toBe("fp16");
        });

        test("removes PQ encoder with warning", () => {
            const consoleSpy = jest.spyOn(console, 'error').mockImplementation();
            
            const params = { 
                m: 16, 
                ef_construction: 100,
                encoder: { name: "pq", parameters: { code_size: 8, m: 4 } }
            };
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(128, methodConfig("hnsw", "lucene", "l2", params))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.parameters.encoder).toBeUndefined();
            expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining("PQ encoder"));
            
            consoleSpy.mockRestore();
        });
    });

    describe("model_id handling", () => {
        test("removes model_id and creates default HNSW config", () => {
            const consoleSpy = jest.spyOn(console, 'error').mockImplementation();
            
            const field = new Map([
                ["type", "knn_vector"],
                ["dimension", 768],
                ["model_id", "test-model-123"]
            ]);
            const inMap = wrapMappings(new Map([["embedding", field]]));
            const outMap = transformMapping(inMap);
            const outField = unwrapField(outMap, "embedding");
            
            expect(outField.type).toBe("knn_vector");
            expect(outField.dimension).toBe(768);
            expect(outField.model_id).toBeUndefined();
            expect(outField.method.name).toBe("hnsw");
            expect(outField.method.engine).toBe("faiss");
            expect(outField.method.parameters.m).toBe(16);
            expect(outField.method.parameters.ef_construction).toBe(100);
            expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining("model_id"));
            
            consoleSpy.mockRestore();
        });
    });

    describe("Default parameter handling", () => {
        test("uses Faiss defaults when no parameters specified", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(128, methodConfig("hnsw", "lucene", "l2", {}))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.parameters.m).toBe(16);
            expect(field.method.parameters.ef_construction).toBe(100);
        });

        test("defaults space_type to l2 when not specified", () => {
            const inMap = wrapMappings(new Map([
                ["embedding", knnField(128, methodConfig("hnsw", "lucene", null, {}))]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "embedding");
            
            expect(field.method.space_type).toBe("l2");
        });
    });

    describe("Pass-through scenarios", () => {
        test("does not modify non-knn fields", () => {
            const inMap = wrapMappings(new Map([
                ["title", new Map([["type", "text"]])],
                ["count", new Map([["type", "integer"]])]
            ]));
            const outMap = transformMapping(inMap);
            
            const title = unwrapField(outMap, "title");
            const count = unwrapField(outMap, "count");
            
            expect(title.get("type")).toBe("text");
            expect(count.get("type")).toBe("integer");
        });

        test("handles indices without knn_vector fields", () => {
            const inMap = wrapMappings(new Map([
                ["name", new Map([["type", "keyword"]])]
            ]));
            const outMap = transformMapping(inMap);
            const field = unwrapField(outMap, "name");
            
            expect(field.get("type")).toBe("keyword");
        });

        test("handles empty mappings", () => {
            const inMap = new Map([
                ["body", new Map([
                    ["mappings", new Map()]
                ])]
            ]);
            const outMap = transformMapping(inMap);
            expect(outMap).toBeDefined();
        });

        test("handles missing mappings", () => {
            const inMap = new Map([
                ["body", new Map()]
            ]);
            const outMap = transformMapping(inMap);
            expect(outMap).toBeDefined();
        });

        test("handles null input", () => {
            const outMap = transformMapping(null);
            expect(outMap).toBeNull();
        });
    });

    describe("Nested field handling", () => {
        test("transforms nested knn_vector fields", () => {
            const nestedProps = new Map([
                ["embedding", knnField(128, methodConfig("hnsw", "lucene", "l2", { m: 16, ef_construction: 100 }))]
            ]);
            const inMap = wrapMappings(new Map([
                ["nested_field", new Map([
                    ["type", "nested"],
                    ["properties", nestedProps]
                ])]
            ]));
            const outMap = transformMapping(inMap);
            
            const nestedField = unwrapField(outMap, "nested_field");
            const embedding = nestedField.get("properties").get("embedding");
            
            // The nested knn_vector should be transformed
            expect(embedding.method.engine).toBe("faiss");
        });

        test("transforms multiple knn_vector fields in same index", () => {
            const inMap = wrapMappings(new Map([
                ["embedding1", knnField(128, methodConfig("hnsw", "lucene", "l2", {}))],
                ["embedding2", knnField(256, methodConfig("hnsw", "nmslib", "innerproduct", {}))]
            ]));
            const outMap = transformMapping(inMap);
            
            const field1 = unwrapField(outMap, "embedding1");
            const field2 = unwrapField(outMap, "embedding2");
            
            expect(field1.method.engine).toBe("faiss");
            expect(field2.method.engine).toBe("faiss");
            expect(field1.dimension).toBe(128);
            expect(field2.dimension).toBe(256);
        });
    });

    describe("Plain object handling", () => {
        test("transforms plain object knn_vector fields", () => {
            const input = wrapMappingsPlainObject({
                embedding: {
                    type: "knn_vector",
                    dimension: 128,
                    method: {
                        name: "hnsw",
                        engine: "lucene",
                        space_type: "l2",
                        parameters: { m: 24, ef_construction: 200 }
                    }
                }
            });
            const output = transformMapping(input);
            const field = unwrapFieldPlain(output, "embedding");
            
            expect(field.method.engine).toBe("faiss");
            expect(field.method.parameters.m).toBe(24);
        });
    });
});