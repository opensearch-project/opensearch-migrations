/**
 * Converts Elasticsearch 1.x/2.x mappings that still use the obsolete `string`
 * field type to valid Elasticsearch 5.x+ mappings using `text` and `keyword`.
 *
 * ### How it works
 * 1. Walks the mapping recursively (also inside multi‑fields and nested/object
 *    `properties`) looking for a `type: "string"` declaration.
 * 2. Decides whether the replacement should be `text` or `keyword` by looking
 *    at the historic `index` setting:
 *    * `"index": "analyzed"` (or no explicit index) ⟶ **text**
 *    * `"index": "not_analyzed"` ⟶ **keyword**
 *    * `"index": "no"` or `false` ⟶ **keyword** with `index:false`
 * 3. Translates/cleans other parameters for the new types:
 *    * Converts legacy `norms: {enabled: <bool>}` objects to the ES5 boolean
 *      `norms` flag.
 *    * Removes `analyzer`, `search_analyzer`, `fielddata`, `term_vector`,
 *      etc. from keyword fields (they are invalid on `keyword`).
 *    * Drops `doc_values` from text fields (still allowed for keyword).
 *    * Drops `null_value` if the field becomes `text` (only valid on keyword).
 *    * Leaves multi‑field definitions intact, but recursively updates any
 *      string sub‑fields.
 * 4. Returns a **new** mapping object; the original input is left untouched.
 *
 * ### Examples
 * ```js
 * // Example 1 – analyzed string ➜ text
 * transformMapping({
 *   properties: { title: { type: "string", index: "analyzed" } }
 * }).properties.title.type       // "text"
 *
 * // Example 2 – not_analyzed string ➜ keyword
 * transformMapping({
 *   properties: { tag: { type: "string", index: "not_analyzed" } }
 * }).properties.tag.type         // "keyword"
 *
 * // Example 3 – multi‑field .raw
 * transformMapping({
 *   properties: {
 *     author: {
 *       type: "string",
 *       fields: { raw: { type: "string", index: "not_analyzed" } }
 *     }
 *   }
 * })
 * // ⇒ { author: { type:"text", fields:{ raw:{ type:"keyword" } } } }
 *
 * // Example 4 – unindexed string
 * transformMapping({
 *   properties: { blob: { type: "string", index: "no", doc_values: false } }
 * })
 * // ⇒ { blob: { type:"keyword", index:false, doc_values:false } }
 * ```
 *
 * @param {object} es2Mapping The mapping object as returned by the ES 1/2
 *   `GET /<index>/_mapping` API – expected to contain a top‑level `properties`
 *   key (but the function also accepts the inner `{properties:…}` object
 *   directly).
 * @returns {object} ES5‑compatible mapping where every legacy string field has
 *   been replaced with `text`/`keyword` plus corrected parameters.
 *
 * @author 2025 Andre’s Elastic migration helper
 */
function transformMapping(es2Mapping) {
    function transformProps(props) {
        const out = {};
        for (const [fieldName, fieldDef] of props) {
            if (fieldDef && fieldDef.type === 'string') {
                const def = { ...fieldDef }; // shallow clone
                const idx = def.index;       // may be undefined

                // Decide new type
                const isNotAnalyzed = idx === 'not_analyzed';
                const isNo = idx === 'no' || idx === false;
                def.type = isNotAnalyzed || isNo ? 'keyword' : 'text';

                // Rewrite/clean index property
                if (idx === 'analyzed' || idx === 'not_analyzed') {
                    delete def.index;                          // default `true`
                } else if (idx === 'no') {
                    def.index = false;
                }

                // Convert norms object → boolean
                if (def.norms && typeof def.norms === 'object' &&
                    Object.prototype.hasOwnProperty.call(def.norms, 'enabled')) {
                    def.norms = !!def.norms.enabled;
                }

                if (def.type === 'keyword') {
                    // Remove analysis‑specific settings invalid for keyword
                    delete def.analyzer;
                    delete def.search_analyzer;
                    delete def.position_increment_gap;
                    delete def.term_vector;
                    delete def.fielddata;
                } else { // text field
                    // doc_values are not allowed on text; fielddata discouraged
                    delete def.doc_values;
                }

                // Null‑value only allowed on keyword
                if (def.type === 'text' && def.null_value !== undefined) {
                    delete def.null_value;
                }

                // Recursively fix sub‑fields
                if (def.fields) {
                    def.fields = transformProps(def.fields);
                }
                out[fieldName] = def;
            } else if (fieldDef && fieldDef.properties) {
                out[fieldName] = {
                    ...fieldDef,
                    properties: transformProps(fieldDef.properties)
                };
            } else if (fieldDef && fieldDef.fields) {
                out[fieldName] = {
                    ...fieldDef,
                    fields: transformProps(fieldDef.fields)
                };
            } else {
                out[fieldName] = fieldDef; // unchanged
            }
        }
        return out;
    }

    if (Array.isArray(es2Mapping)) {
        return es2Mapping.map(transformMapping);
    }

    // Accept mappings already at the {properties:{…}} level
    if (es2Mapping && es2Mapping.properties) {
        return { ...es2Mapping, properties: transformProps(es2Mapping.properties) };
    }
    // Or full index mappings ({ <type>: { properties:{…} } })
    const outMapping = {};
    for (const [typeName, typeMapping] of es2Mapping) {
        if (typeMapping && typeMapping.properties) {
            outMapping[typeName] = {
                ...typeMapping,
                properties: transformProps(typeMapping.properties)
            };
        } else {
            outMapping[typeName] = typeMapping;
        }
    }
    return outMapping;
}

function main(ignoredContext) {
    return (metadata) => {
        if (metadata && metadata.body && metadata.body.mappings) {
            return {
                ...metadata,
                body: {
                    ...(metadata.body),
                    mappings: transformMapping(metadata.body.mappings)

                }
            }
        }
        return metadata;
    }
}

(() => main)();