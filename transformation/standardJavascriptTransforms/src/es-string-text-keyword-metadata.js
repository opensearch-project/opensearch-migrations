/**
 * Transform an entire mapping (or an array of mappings) so that legacy
 * "string" field definitions are replaced with modern "text" / "keyword"
 * equivalents.  Works recursively on nested `properties` and `fields`, all
 * represented as `Map` instances.
 */
function transformMapping(mappings) {
    // ────────────────────────────────────────────────────────────────────
    // Helpers (Map-centric versions of the original object functions)
    // ────────────────────────────────────────────────────────────────────

    const isString = d => d instanceof Map && d.get("type") === "string";

    const convertNorms = def => {
        const norms = def.get("norms");
        if (norms instanceof Map && norms.has("enabled")) {
            def.set("norms", !!norms.get("enabled"));
        }
    };

    const cleanIndex = def => {
        const idx = def.get("index");
        switch (idx) {
            case "analyzed":
            case "not_analyzed":
                def.delete("index"); // ES5 default is `true`
                break;
            case "no":
                def.set("index", false);
                break;
        }
    };

    const cleanByType = def => {
        const type = def.get("type");
        if (type === "keyword") {
            [
                "analyzer",
                "search_analyzer",
                "position_increment_gap",
                "term_vector",
                "fielddata"
            ].forEach(k => def.delete(k));
        } else { // text field
            def.delete("doc_values");
            if (def.has("null_value")) def.delete("null_value");
        }
    };

    const transformString = fieldDef => {
        const idx = fieldDef.get("index");
        const isKeyword = ["not_analyzed", "no", false].includes(idx);
        fieldDef.set("type", isKeyword ? "keyword" : "text");

        cleanIndex(fieldDef);
        convertNorms(fieldDef);
        cleanByType(fieldDef);

        // Recurse into multi-fields
        if (fieldDef.has("fields")) {
            const fieldsMap = fieldDef.get("fields");
            fieldDef.set("fields", transformMapping(fieldsMap));
        }
        return fieldDef;
    };

    const transformDef = def => {
        if (isString(def)) return transformString(def);
        return def;
    };
    // Handle arrays (e.g., dynamic templates list) first.
    if (Array.isArray(mappings)) {
        return mappings.map(transformMapping);
    }

    if (mappings instanceof Map) {
        mappings = transformDef(mappings);

        for (const [name, def] of mappings) {
            mappings.set(name, transformMapping(def));
        }
    }

    return mappings;
}

function main(ignoredContext) {
    return metadata => {
        if (metadata?.get("body")?.has("mappings")) {
            metadata.get("body").set("mappings", transformMapping(metadata.get("body").get("mappings")));
        }
        return metadata;
    }
}

// Visibility for testing
if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();