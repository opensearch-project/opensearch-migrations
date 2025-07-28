/**
 * Converts Elasticsearch 1.x/2.x “string” mappings to Elasticsearch 5+ “text/keyword”.
 *
 * - Walks mappings recursively, including multi-fields and nested/object `properties`.
 * - Chooses `text` vs `keyword` from the historic `index` value.
 * - Adjusts/deletes settings that became invalid (e.g. `analyzer` on keyword, `doc_values` on text).
 * - Converts legacy `norms:{enabled:…}` objects to the ES5 boolean `norms` flag.
 * - Returns a **new** mapping; the original input object is left untouched.
 *
 */
function transformMapping(es2Mapping) {
    const isString  = d => d && d.type === 'string';
    const clone     = o => ({ ...o });

    const convertNorms = def => {
        if (def.norms && typeof def.norms === 'object' && 'enabled' in def.norms) {
            def.norms = !!def.norms.enabled;
        }
    };

    const cleanIndex = def => {
        switch (def.index) {
            case 'analyzed':
            case 'not_analyzed':
                delete def.index;            // ES5 default is `true`
                break;
            case 'no':
                def.index = false;
                break;
        }
    };

    const cleanByType = def => {
        if (def.type === 'keyword') {
            [
                'analyzer',
                'search_analyzer',
                'position_increment_gap',
                'term_vector',
                'fielddata'
            ].forEach(k => delete def[k]);
        } else {                         // text field
            delete def.doc_values;
            if ('null_value' in def) delete def.null_value;
        }
    };

    const transformString = fieldDef => {
        const out = clone(fieldDef);
        const idx = out.index;
        out.type  = ['not_analyzed', 'no', false].includes(idx) ? 'keyword' : 'text';

        cleanIndex(out);
        convertNorms(out);
        cleanByType(out);

        if (out.fields) out.fields = transformProps(out.fields);
        return out;
    };

    const transformDef = def => {
        if (isString(def))          return transformString(def);
        if (def && def.properties)  return { ...def, properties: transformProps(def.properties) };
        if (def && def.fields)      return { ...def, fields: transformProps(def.fields) };
        return def;                 // leave unchanged
    };

    function transformProps(props) {
        return Object.entries(props).reduce(
            (acc, [name, def]) => (acc[name] = transformDef(def), acc), {}
        );
    }

    if (Array.isArray(es2Mapping)) {
        return es2Mapping.map(transformMapping);
    }

    if (es2Mapping && es2Mapping.properties) {
        return { ...es2Mapping, properties: transformProps(es2Mapping.properties) };
    }

    return Object.entries(es2Mapping).reduce((acc, [typeName, typeMapping]) => {
        acc[typeName] = typeMapping && typeMapping.properties
            ? { ...typeMapping, properties: transformProps(typeMapping.properties) }
            : typeMapping;
        return acc;
    }, {});
}

function main(ignoredContext) {
    return metadata =>
        metadata && metadata.body && metadata.body.mappings
            ? {
                ...metadata,
                body: {
                    ...metadata.body,
                    mappings: transformMapping(metadata.body.mappings)
                }
            }
            : metadata;
}

(() => main)();