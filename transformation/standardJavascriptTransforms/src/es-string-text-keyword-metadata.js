// src/transformMapping.js
// --------------------------------------------
// Convert an ES 2.x-style string mapping (nested Maps) so that
//     { type: 'string', index: 'analyzed' | 'not_analyzed' | 'no' }
// becomes the modern { type: 'text' | 'keyword' } form, while also
// cleaning up legacy properties.  All inputs are treated as immutable –
// the function returns freshly-cloned Maps.
//
// Public API: `transformMapping(mapping: Map | Map[] | any) => same shape`
// --------------------------------------------

/**
 * Transform an entire mapping (or an array of mappings) so that legacy
 * "string" field definitions are replaced with modern "text" / "keyword"
 * equivalents.  Works recursively on nested `properties` and `fields`, all
 * represented as `Map` instances.
 *
 * @param {*} es2Mapping – a Map (or array of Maps) in ES 2.x format.
 * @returns {*} A new mapping structure with the same overall shape.
 */
function transformMapping(es2Mapping) {
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
        const out = new Map(fieldDef);          // shallow clone
        const idx = out.get("index");
        const isKeyword = ["not_analyzed", "no", false].includes(idx);
        out.set("type", isKeyword ? "keyword" : "text");

        cleanIndex(out);
        convertNorms(out);
        cleanByType(out);

        // Recurse into multi-fields
        if (out.has("fields")) {
            const fieldsMap = out.get("fields");
            out.set("fields", transformMapping(fieldsMap));
        }
        return out;
    };

    const transformDef = def => {
        if (isString(def)) return transformString(def);
        return def;
    };
    // Handle arrays (e.g., dynamic templates list) first.
    if (Array.isArray(es2Mapping)) {
        return es2Mapping.map(transformMapping);
    }

    if (es2Mapping instanceof Map) {
        es2Mapping = transformDef(es2Mapping);

        for (const [name, def] of es2Mapping) {
            es2Mapping.set(name, transformMapping(def));
        }
    }

    return es2Mapping;
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