/**
 * Lift ES 7.x index-level k-NN build params onto field-level method config.
 *
 * The ES 7.x AWS k-NN plugin stored build-time params as index settings
 * (index.knn.algo_param.ef_construction, index.knn.algo_param.m,
 * index.knn.space_type) and read them only with the nmslib engine, which was
 * removed in OpenSearch 3.0. OpenSearch 2.x+ rejects those keys on create-index
 * ("unknown setting") and instead expects them inside each knn_vector field's
 * `method` block. This strips the index-level params and injects an HNSW method
 * built from them onto every knn_vector field lacking one.
 *
 * The engine defaults to faiss (the canonical nmslib successor; with
 * Lucene-on-Faiss the memory advantage that once favored the lucene engine no
 * longer requires it) and can be overridden via context.engine. nmslib-only
 * space types (l1, linf) are unsupported by faiss/lucene and fall back to l2.
 *
 * Left untouched: index.knn (the enable flag, still valid) and
 * index.knn.algo_param.ef_search (a runtime setting, still valid on OS).
 * A field that already declares its own `method` always wins. No-op when no
 * index-level build params are present, so it is safe to default-enable.
 */

const DEFAULT_SPACE_TYPE = 'l2';
const DEFAULT_ENGINE = 'faiss';
// faiss/lucene HNSW space types for float vectors; matches knn-to-serverless-metadata.js.
const SUPPORTED_SPACE_TYPES = new Set(['l2', 'innerproduct', 'cosinesimil']);

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

function deleteProp(obj, key) {
    if (!obj) return;
    if (obj instanceof Map) {
        obj.delete(key);
    } else {
        delete obj[key];
    }
}

function hasProp(obj, key) {
    if (!obj) return false;
    return obj instanceof Map ? obj.has(key) : Object.hasOwn(obj, key);
}

// Settings may arrive as a literal flat key ("index.knn.space_type") or as a
// nested object (settings.index.knn.space_type); read/delete handle both.
function readSetting(settings, dottedPath) {
    if (!settings) return undefined;
    if (hasProp(settings, dottedPath)) {
        return getProp(settings, dottedPath);
    }
    const parts = dottedPath.split('.');
    let node = settings;
    for (const part of parts) {
        if (!node || typeof node !== 'object') return undefined;
        if (!hasProp(node, part)) return undefined;
        node = getProp(node, part);
    }
    return node;
}

function deleteSetting(settings, dottedPath) {
    if (!settings) return;
    if (hasProp(settings, dottedPath)) {
        deleteProp(settings, dottedPath);
    }
    const parts = dottedPath.split('.');
    const stack = [settings];
    let node = settings;
    for (let i = 0; i < parts.length - 1; i++) {
        if (!node || typeof node !== 'object' || !hasProp(node, parts[i])) {
            return;
        }
        node = getProp(node, parts[i]);
        stack.push(node);
    }
    deleteProp(node, parts.at(-1));
    // Prune intermediate objects emptied by the removal so the target does not
    // receive a stray "index": {} block.
    for (let j = stack.length - 1; j > 0; j--) {
        const n = stack[j];
        const empty = n instanceof Map ? n.size === 0 : Object.keys(n).length === 0;
        if (!empty) break;
        deleteProp(stack[j - 1], parts[j - 1]);
    }
}

// ES serializes numeric settings as strings; coerce back to numbers, leaving
// anything non-numeric untouched.
function asNumber(v) {
    if (v == null) return undefined;
    if (typeof v === 'number') return v;
    if (typeof v === 'string' && v !== '' && !Number.isNaN(Number(v))) return Number(v);
    return v;
}

// nmslib accepted l1/linf, which faiss and lucene do not; fall back to l2.
function normalizeSpaceType(spaceType) {
    if (spaceType === undefined) return DEFAULT_SPACE_TYPE;
    const normalized = String(spaceType).toLowerCase();
    if (SUPPORTED_SPACE_TYPES.has(normalized)) return normalized;
    console.error("WARNING: space_type '" + spaceType + "' has no faiss/lucene equivalent; falling back to '" + DEFAULT_SPACE_TYPE + "'. This will affect search results.");
    return DEFAULT_SPACE_TYPE;
}

function buildMethodFromIndexParams(params, engine) {
    const method = {
        name: 'hnsw',
        engine: engine,
        space_type: normalizeSpaceType(params.spaceType)
    };
    const parameters = {};
    if (params.m !== undefined) parameters.m = asNumber(params.m);
    if (params.efConstruction !== undefined) parameters.ef_construction = asNumber(params.efConstruction);
    if (Object.keys(parameters).length > 0) {
        method.parameters = parameters;
    }
    return method;
}

function injectMethodIntoKnnVectors(node, method) {
    if (!node || typeof node !== 'object') return;
    const entries = node instanceof Map ? node.entries() : Object.entries(node);
    for (const [, val] of entries) {
        if (val && typeof val === 'object') {
            if (getProp(val, 'type') === 'knn_vector' && !hasProp(val, 'method')) {
                setProp(val, 'method', method);
            } else {
                injectMethodIntoKnnVectors(val, method);
            }
        }
    }
}

function applyIndexLevelKnn(raw, engine) {
    const body = getProp(raw, 'body');
    if (!body) return false;

    const settings = getProp(body, 'settings');
    const mappings = getProp(body, 'mappings');

    const efConstruction = readSetting(settings, 'index.knn.algo_param.ef_construction');
    const m = readSetting(settings, 'index.knn.algo_param.m');
    const spaceType = readSetting(settings, 'index.knn.space_type');

    const hasIndexLevelKnn = efConstruction !== undefined ||
        m !== undefined ||
        spaceType !== undefined;

    if (!hasIndexLevelKnn) {
        return false;
    }

    if (settings) {
        deleteSetting(settings, 'index.knn.algo_param.ef_construction');
        deleteSetting(settings, 'index.knn.algo_param.m');
        deleteSetting(settings, 'index.knn.space_type');
    }

    if (mappings) {
        const method = buildMethodFromIndexParams({
            efConstruction: efConstruction,
            m: m,
            spaceType: spaceType
        }, engine);
        injectMethodIntoKnnVectors(mappings, method);
    }

    return true;
}

function main(context) {
    const engine = getProp(context, 'engine') || DEFAULT_ENGINE;
    return function (raw) {
        applyIndexLevelKnn(raw, engine);
        return raw;
    };
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();
