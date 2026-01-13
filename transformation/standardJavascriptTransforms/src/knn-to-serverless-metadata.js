/**
 * Transform knn_vector fields for OpenSearch Serverless compatibility.
 *
 * OpenSearch Serverless only supports:
 * - Faiss engine (not Lucene or NMSLIB)
 * - HNSW method (not IVF)
 * - No training APIs (no model_id)
 * - No PQ encoder (only SQ/flat)
 * - Space types: l2, innerproduct, cosinesimil
 *
 * This transformer converts incompatible configurations to Faiss HNSW.
 */

/**
 * Helper to get a property from either a Map or plain object.
 */
function getProp(obj, key) {
    if (!obj) return undefined;
    return obj instanceof Map ? obj.get(key) : obj[key];
}

/**
 * Convert space_type to serverless-compatible value.
 * l1 and linf are not supported, convert to l2 with warning.
 */
function convertSpaceType(spaceType) {
    if (!spaceType) return 'l2';
    const normalized = spaceType.toLowerCase();
    switch (normalized) {
        case 'l2':
        case 'innerproduct':
        case 'cosinesimil':
            return normalized;
        case 'l1':
        case 'linf':
            console.error(`WARNING: space_type '${spaceType}' is not supported on OpenSearch Serverless. Converting to 'l2'. This will affect search results.`);
            return 'l2';
        default:
            return 'l2';
    }
}

/**
 * Build Faiss HNSW method configuration from existing method or defaults.
 * Preserves m and ef_construction if present, otherwise uses defaults.
 */
function buildFaissHnswMethod(existingMethod) {
    const params = getProp(existingMethod, 'parameters') || {};

    // Get m and ef_construction from existing config or use Faiss defaults
    const m = getProp(params, 'm') || 16;
    const efConstruction = getProp(params, 'ef_construction') || 100;

    // Determine space_type
    const spaceType = convertSpaceType(getProp(existingMethod, 'space_type'));

    const method = {
        name: 'hnsw',
        engine: 'faiss',
        space_type: spaceType,
        parameters: {
            m: m,
            ef_construction: efConstruction
        }
    };

    // Handle encoder - only SQ (scalar quantization) is supported on serverless
    // PQ (product quantization) requires training APIs which serverless doesn't support
    const existingEncoder = getProp(params, 'encoder');
    if (existingEncoder) {
        const encoderName = getProp(existingEncoder, 'name')?.toLowerCase();
        if (encoderName === 'sq' || encoderName === 'scalar') {
            // SQ encoder is supported - convert to Faiss format
            method.parameters.encoder = {
                name: 'sq',
                parameters: {
                    type: 'fp16'
                }
            };
        } else if (encoderName === 'pq') {
            // PQ encoder NOT supported - remove it with warning
            console.error('WARNING: PQ encoder is not supported on OpenSearch Serverless (requires training APIs). Removing encoder configuration.');
        }
        // 'flat' encoder or no encoder = no encoder config needed
    }

    return method;
}

/**
 * Check if a knn_vector field needs transformation for serverless.
 */
function needsTransformation(def) {
    const type = getProp(def, 'type');
    if (type !== 'knn_vector') return false;

    // Has model_id - needs transformation (training not supported)
    if (getProp(def, 'model_id')) return true;

    const method = getProp(def, 'method');
    if (!method) return false;

    // Non-faiss engine needs transformation
    const engine = getProp(method, 'engine')?.toLowerCase();
    if (engine && engine !== 'faiss') return true;

    // IVF method needs transformation (only HNSW supported)
    const methodName = getProp(method, 'name')?.toLowerCase();
    if (methodName === 'ivf') return true;

    // PQ encoder needs transformation
    const params = getProp(method, 'parameters');
    const encoder = getProp(params, 'encoder');
    if (getProp(encoder, 'name')?.toLowerCase() === 'pq') return true;

    // Unsupported space types need transformation
    const spaceType = getProp(method, 'space_type')?.toLowerCase();
    if (spaceType === 'l1' || spaceType === 'linf') return true;

    return false;
}

/**
 * Transform a knn_vector field definition for serverless compatibility.
 */
function transformKnnField(def) {
    const dimension = getProp(def, 'dimension');

    // Handle model_id case - remove model_id, create default HNSW method
    if (getProp(def, 'model_id')) {
        console.error('WARNING: model_id is not supported on OpenSearch Serverless (requires training APIs). Removing model_id and using default HNSW configuration.');
        return {
            type: 'knn_vector',
            dimension: dimension,
            method: buildFaissHnswMethod(null)
        };
    }

    // Transform method to Faiss HNSW
    return {
        type: 'knn_vector',
        dimension: dimension,
        method: buildFaissHnswMethod(getProp(def, 'method'))
    };
}

/**
 * Recursively traverse mappings and transform knn_vector fields.
 * Handles both Map and plain object nodes.
 */
function recurse(node) {
    if (!node || typeof node !== 'object') return;

    // Iterate entries uniformly, regardless of Map or plain object
    const entries = node instanceof Map ? node.entries() : Object.entries(node);

    for (const [key, val] of entries) {
        if (val && typeof val === 'object') {
            if (needsTransformation(val)) {
                const transformed = transformKnnField(val);
                if (node instanceof Map) {
                    node.set(key, transformed);
                } else {
                    node[key] = transformed;
                }
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

// Visibility for testing
if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();
