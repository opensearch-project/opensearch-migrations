/**
 * Transform ES 8.15+ semantic_text fields to OpenSearch 3.0+ semantic fields.
 *
 * OpenSearch 3.0+ has a semantic field type in the neural-search plugin.
 * This transformer converts semantic_text → semantic, renaming
 * inference_id → model_id and search_inference_id → search_model_id,
 * and removing ES-specific model_settings.
 *
 * If model_mappings are provided in context (from inference endpoint migration),
 * the actual OS model_ids are used instead of the ES inference_id values.
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

function deleteProp(obj, key) {
    if (obj instanceof Map) {
        obj.delete(key);
    } else {
        delete obj[key];
    }
}

function resolveModelId(modelMappings, inferenceId) {
    if (!modelMappings || !inferenceId) return inferenceId;
    var mapped = getProp(modelMappings, inferenceId);
    return mapped !== undefined ? mapped : inferenceId;
}

// ES properties that get renamed to OS equivalents
var RENAME_MAP = {
    'inference_id': 'model_id',
    'search_inference_id': 'search_model_id'
};

function recurse(node, modelMappings) {
    if (!node || typeof node !== 'object') return false;

    var entries = node instanceof Map ? node.entries() : Object.entries(node);
    var changed = false;

    for (var pair of entries) {
        var key = pair[0];
        var val = pair[1];
        if (val && typeof val === 'object') {
            if (getProp(val, 'type') === 'semantic_text') {
                setProp(val, 'type', 'semantic');
                // Rename inference_id -> model_id, search_inference_id -> search_model_id
                for (var esProp in RENAME_MAP) {
                    var value = getProp(val, esProp);
                    if (value !== undefined) {
                        var resolved = resolveModelId(modelMappings, value);
                        setProp(val, RENAME_MAP[esProp], resolved);
                        deleteProp(val, esProp);
                    }
                }
                // Remove ES-only model_settings
                deleteProp(val, 'model_settings');
                changed = true;
            } else {
                changed = recurse(val, modelMappings) || changed;
            }
        }
    }

    return changed;
}

function applyTransformation(raw, modelMappings) {
    var body = getProp(raw, 'body');
    var mappings = getProp(body, 'mappings');

    if (mappings && recurse(mappings, modelMappings)) {
        // semantic field type requires index.knn=true
        var settings = getProp(body, 'settings');
        if (!settings) {
            settings = {};
            setProp(body, 'settings', settings);
        }
        var indexSettings = getProp(settings, 'index');
        if (!indexSettings) {
            indexSettings = {};
            setProp(settings, 'index', indexSettings);
        }
        setProp(indexSettings, 'knn', true);
    }

    return raw;
}

function main(context) {
    var modelMappings = null;
    if (context) {
        modelMappings = getProp(context, 'model_mappings');
    }

    return function(raw) {
        return applyTransformation(raw, modelMappings);
    };
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = main;
}

(() => main)();
