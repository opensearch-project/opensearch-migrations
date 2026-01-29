/**
 * Transform nmslib knn_vector fields for OpenSearch 3.0+ compatibility.
 *
 * OpenSearch 3.0 deprecated the nmslib engine. This transformer converts
 * nmslib configurations to faiss HNSW which is the recommended replacement.
 */

function getProp(obj, key) {
    if (!obj) return undefined;
    return obj instanceof Map ? obj.get(key) : obj[key];
}

function setProp(obj, key, value) {
    if (obj instanceof Map) {
        obj.set(key, value);
    } else {
        obj[key] = value;
    }
}

function needsTransformation(def) {
    const type = getProp(def, 'type');
    if (type !== 'knn_vector') return false;

    const method = getProp(def, 'method');
    if (!method) return false;

    const engine = getProp(method, 'engine')?.toLowerCase();
    return engine === 'nmslib';
}

function transformKnnField(def) {
    const dimension = getProp(def, 'dimension');
    const method = getProp(def, 'method');
    const params = getProp(method, 'parameters') || {};

    const m = getProp(params, 'm') || 16;
    const efConstruction = getProp(params, 'ef_construction') || 100;
    const spaceType = getProp(method, 'space_type') || 'l2';

    return {
        type: 'knn_vector',
        dimension: dimension,
        method: {
            name: 'hnsw',
            engine: 'faiss',
            space_type: spaceType,
            parameters: {
                m: m,
                ef_construction: efConstruction
            }
        }
    };
}

function recurse(node) {
    if (!node || typeof node !== 'object') return;

    const entries = node instanceof Map ? node.entries() : Object.entries(node);

    for (const [key, val] of entries) {
        if (val && typeof val === 'object') {
            if (needsTransformation(val)) {
                const transformed = transformKnnField(val);
                setProp(node, key, transformed);
            } else {
                recurse(val);
            }
        }
    }
}

function applyTransformation(raw) {
    const body = getProp(raw, 'body');
    const mappings = getProp(body, 'mappings');

    if (mappings) {
        recurse(mappings);
    }

    return raw;
}

function main(context) {
    return applyTransformation;
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();
