const main = require("../src/analysis-component-removal");

describe("analysis-component-removal transformer", () => {
    test("noop when context has no rules", () => {
        const transformer = main({});
        const doc = { type: "index", name: "x", body: { settings: {} } };
        const result = transformer(doc);
        expect(result).toBe(doc);
    });

    test("strips removed token filter from analyzer.filter array (object form)", () => {
        const transformer = main({ removed: { filter: ["standard"] } });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    analysis: {
                        analyzer: {
                            my_a: {
                                type: "custom",
                                tokenizer: "standard",
                                filter: ["standard", "lowercase", "asciifolding"],
                            },
                        },
                    },
                },
            },
        };
        const result = transformer(doc);
        const filter = result.body.settings.analysis.analyzer.my_a.filter;
        expect(filter).toEqual(["lowercase", "asciifolding"]);
        // tokenizer "standard" is a different namespace and must NOT be touched
        expect(result.body.settings.analysis.analyzer.my_a.tokenizer).toBe("standard");
    });

    test("strips removed tokenizer reference from analyzer", () => {
        const transformer = main({ removed: { tokenizer: ["legacy_tok"] } });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    analysis: {
                        analyzer: {
                            my_a: { tokenizer: "legacy_tok", filter: [] },
                        },
                    },
                },
            },
        };
        const result = transformer(doc);
        // The tokenizer key should be removed from the analyzer (no replacement specified)
        expect(result.body.settings.analysis.analyzer.my_a).not.toHaveProperty("tokenizer");
    });

    test("renames token filter using the renames map", () => {
        const transformer = main({
            renames: { filter: { delimited_payload_filter: "delimited_payload" } },
        });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    analysis: {
                        analyzer: {
                            my_a: { filter: ["delimited_payload_filter", "lowercase"] },
                        },
                    },
                },
            },
        };
        const result = transformer(doc);
        expect(result.body.settings.analysis.analyzer.my_a.filter).toEqual([
            "delimited_payload",
            "lowercase",
        ]);
    });

    test("removes a top-level analyzer/filter/tokenizer entry by name", () => {
        const transformer = main({
            removed: { analyzer: ["bad_analyzer"], filter: ["bad_filter"] },
        });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    analysis: {
                        analyzer: {
                            bad_analyzer: { type: "standard" },
                            good_analyzer: { type: "standard" },
                        },
                        filter: {
                            bad_filter: { type: "lowercase" },
                            good_filter: { type: "lowercase" },
                        },
                    },
                },
            },
        };
        const result = transformer(doc);
        expect(result.body.settings.analysis.analyzer).not.toHaveProperty("bad_analyzer");
        expect(result.body.settings.analysis.analyzer).toHaveProperty("good_analyzer");
        expect(result.body.settings.analysis.filter).not.toHaveProperty("bad_filter");
        expect(result.body.settings.analysis.filter).toHaveProperty("good_filter");
    });

    test("supports settings.index.analysis layout", () => {
        const transformer = main({ removed: { filter: ["standard"] } });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    index: {
                        analysis: {
                            analyzer: {
                                my_a: { filter: ["standard", "lowercase"] },
                            },
                        },
                    },
                },
            },
        };
        const result = transformer(doc);
        expect(result.body.settings.index.analysis.analyzer.my_a.filter).toEqual([
            "lowercase",
        ]);
    });

    test("supports template.settings layout (component/index templates)", () => {
        const transformer = main({ removed: { filter: ["standard"] } });
        const doc = {
            type: "indexTemplate",
            name: "t",
            body: {
                template: {
                    settings: {
                        analysis: {
                            analyzer: { my_a: { filter: ["standard", "lowercase"] } },
                        },
                    },
                },
            },
        };
        const result = transformer(doc);
        expect(result.body.template.settings.analysis.analyzer.my_a.filter).toEqual([
            "lowercase",
        ]);
    });

    test("works with Map-based body (graal interop shape)", () => {
        const transformer = main(
            new Map([["removed", { filter: ["standard"] }]])
        );
        const body = new Map([
            ["settings", new Map([
                ["analysis", new Map([
                    ["analyzer", new Map([
                        ["my_a", new Map([
                            ["filter", ["standard", "lowercase"]],
                            ["tokenizer", "standard"],
                        ])],
                    ])],
                ])],
            ])],
        ]);
        const doc = new Map([
            ["type", "index"],
            ["name", "x"],
            ["body", body],
        ]);
        const result = transformer(doc);
        const filter = result.get("body").get("settings").get("analysis")
            .get("analyzer").get("my_a").get("filter");
        expect(filter).toEqual(["lowercase"]);
        const tok = result.get("body").get("settings").get("analysis")
            .get("analyzer").get("my_a").get("tokenizer");
        expect(tok).toBe("standard");
    });

    test("no-op when document has no analysis section", () => {
        const transformer = main({ removed: { filter: ["standard"] } });
        const doc = { type: "index", name: "x", body: { settings: { index: { number_of_shards: 1 } } } };
        const result = transformer(doc);
        expect(result).toBe(doc);
        expect(result.body.settings.index.number_of_shards).toBe(1);
    });

    test("processes arrays of documents", () => {
        const transformer = main({ removed: { filter: ["standard"] } });
        const docs = [
            { type: "index", name: "a", body: { settings: { analysis: { analyzer: { z: { filter: ["standard"] } } } } } },
            { type: "index", name: "b", body: { settings: { analysis: { analyzer: { z: { filter: ["standard", "lowercase"] } } } } } },
        ];
        const result = transformer(docs);
        expect(result[0].body.settings.analysis.analyzer.z.filter).toEqual([]);
        expect(result[1].body.settings.analysis.analyzer.z.filter).toEqual(["lowercase"]);
    });

    test("strips standard filter from flat-dotted analyzer.filter array (snapshot upstream form)", () => {
        const transformer = main({ removed: { filter: ["standard"] } });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    "index.analysis.analyzer.alcatraz_tokenized_string.filter":
                        ["standard", "alcatraz_pattern_capture", "lowercase", "asciifolding"],
                    "index.analysis.analyzer.alcatraz_tokenized_string.tokenizer": "standard",
                    "index.analysis.analyzer.alcatraz_tokenized_string.type": "custom",
                    "index.number_of_shards": "1",
                },
            },
        };
        const result = transformer(doc);
        const filter = result.body.settings[
            "index.analysis.analyzer.alcatraz_tokenized_string.filter"
        ];
        expect(filter).toEqual([
            "alcatraz_pattern_capture",
            "lowercase",
            "asciifolding",
        ]);
        // Tokenizer reference (string "standard") is a different namespace and must remain.
        expect(
            result.body.settings[
                "index.analysis.analyzer.alcatraz_tokenized_string.tokenizer"
            ]
        ).toBe("standard");
    });

    test("renames flat-dotted analyzer.filter entries", () => {
        const transformer = main({ renames: { filter: { delimited_payload_filter: "delimited_payload" } } });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    "index.analysis.analyzer.a.filter": ["delimited_payload_filter", "lowercase"],
                },
            },
        };
        const result = transformer(doc);
        expect(result.body.settings["index.analysis.analyzer.a.filter"]).toEqual([
            "delimited_payload",
            "lowercase",
        ]);
    });

    test("removes flat-dotted top-level filter entries by name", () => {
        const transformer = main({ removed: { filter: ["bad"] } });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    "index.analysis.filter.bad.type": "stop",
                    "index.analysis.filter.bad.stopwords": ["a", "b"],
                    "index.analysis.filter.good.type": "stop",
                    "index.analysis.filter.good.stopwords": ["c"],
                },
            },
        };
        const result = transformer(doc);
        expect(result.body.settings["index.analysis.filter.bad.type"]).toBeUndefined();
        expect(result.body.settings["index.analysis.filter.bad.stopwords"]).toBeUndefined();
        expect(result.body.settings["index.analysis.filter.good.type"]).toBe("stop");
    });

    test("renames flat-dotted top-level analyzer entries by rewriting keys", () => {
        const transformer = main({ renames: { analyzer: { OldAnalyzer: "new_analyzer" } } });
        const doc = {
            type: "index",
            name: "x",
            body: {
                settings: {
                    "index.analysis.analyzer.OldAnalyzer.type": "custom",
                    "index.analysis.analyzer.OldAnalyzer.tokenizer": "standard",
                },
            },
        };
        const result = transformer(doc);
        expect(result.body.settings["index.analysis.analyzer.OldAnalyzer.type"]).toBeUndefined();
        expect(result.body.settings["index.analysis.analyzer.new_analyzer.type"]).toBe("custom");
        expect(result.body.settings["index.analysis.analyzer.new_analyzer.tokenizer"]).toBe("standard");
    });
});
