/**
 * Transform ES 8.15+ semantic_text fields to OpenSearch text fields.
 *
 * OpenSearch does not have a semantic_text field type. This transformer
 * converts semantic_text definitions to text, removing ES-specific
 * inference properties (inference_id, search_inference_id, model_settings).
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

const ES_ONLY_PROPS = ['inference_id', 'search_inference_id', 'model_settings'];

function recurse(node) {
    if (!node || typeof node !== 'object') return false;

    const entries = node instanceof Map ? node.entries() : Object.entries(node);
    let changed = false;

    for (const [key, val] of entries) {
        if (val && typeof val === 'object') {
            if (getProp(val, 'type') === 'semantic_text') {
                setProp(val, 'type', 'text');
                ES_ONLY_PROPS.forEach(p => deleteProp(val, p));
                changed = true;
            } else {
                changed = recurse(val) || changed;
            }
        }
    }

    return changed;
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
