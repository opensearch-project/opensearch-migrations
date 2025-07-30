const main = require("../src/es-string-text-keyword-metadata");

const transformMapping = main(null);

const strField = (indexVal, extras = {}) => {
    const m = new Map([["type", "string"]]);
    if (indexVal !== undefined) m.set("index", indexVal);
    for (const [k, v] of Object.entries(extras)) m.set(k, v);
    return m;
};

/**
 * Wrap a field‑mapping Map inside the required structure:
 * { body: { mappings: [{ doc: { properties: { values: <fieldsMap> } } }] } }
 */
const wrapValues = fieldsMap =>
    new Map([
        ["body", new Map([
            ["mappings", [new Map([
                ["doc", new Map([
                    ["properties", new Map([
                        ["values", fieldsMap]
                    ])]
                ])]
            ])]]
        ])]
    ]);

const unwrapValues = outMap =>
    outMap.get("body")
        .get("mappings")[0]
        .get("doc")
        .get("properties")
        .get("values");

/* ---------- test cases ---------- */
describe("transformMapping (Map input)", () => {
    test.each([
        ["analyzed  ➜ text"      , "analyzed"   , "text"   , undefined],
        ["not_analyzed ➜ keyword", "not_analyzed", "keyword", undefined],
        ["no (legacy off) ➜ kw"  , "no"         , "keyword", false   ],
        ["false ➜ keyword"       , false        , "keyword", false   ],
    ])("%s", (_label, idx, expectType, expectIndex) => {
        const inMap  = wrapValues(new Map([["title", strField(idx)]]));
        const outMap = transformMapping(inMap);
        const outVals = unwrapValues(outMap);
        expect(outVals.get("title").get("type")).toBe(expectType);
        expect(outVals.get("title").get("index")).toBe(expectIndex);
    });

    test("norms object ➜ boolean", () => {
        const inMap = wrapValues(new Map([
            ["body", strField("analyzed", { norms: new Map([["enabled", false]]) })]
        ]));
        const outMap = transformMapping(inMap);
        const outVals = unwrapValues(outMap);
        expect(outVals.get("body").get("norms")).toBe(false);
    });

    test("keyword-only props are stripped", () => {
        const inMap = wrapValues(new Map([
            ["tag", strField("not_analyzed", { analyzer: "std", fielddata: true })]
        ]));
        const outMap = transformMapping(inMap);
        const outVals = unwrapValues(outMap);
        const outTag = outVals.get("tag");
        ["analyzer", "fielddata"].forEach(k => expect(outTag.has(k)).toBe(false));
    });

    test("text-only props are stripped", () => {
        const inMap = wrapValues(new Map([
            ["msg", strField("analyzed", { doc_values: false, null_value: "N/A" })]
        ]));
        const outMap = transformMapping(inMap);
        const outVals = unwrapValues(outMap);
        const outMsg = outVals.get("msg");
        ["doc_values", "null_value"].forEach(k => expect(outMsg.has(k)).toBe(false));
    });

    test("nested properties & multi-fields recurse", () => {
        const headline = strField("analyzed", {
            fields: new Map([["raw", strField("not_analyzed")]])
        });
        const inMap = wrapValues(new Map([
            ["props", new Map([["properties", new Map([["headline", headline]])]])]
        ]));
        const outMap = transformMapping(inMap);
        const outVals = unwrapValues(outMap);

        const outHeadline = outVals
            .get("props").get("properties").get("headline");
        expect(outHeadline.get("type")).toBe("text");
        expect(outHeadline.get("fields").get("raw").get("type")).toBe("keyword");
    });
});
