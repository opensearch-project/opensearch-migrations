/**
 * Solr-to-OpenSearch metadata transformation.
 *
 * Converts Solr field type names in index mappings to their OpenSearch equivalents.
 * Applied when migrating from a Solr source to an OpenSearch target.
 *
 * Input:  metadata Map with body.mappings.properties containing Solr field types
 * Output: same structure with types converted to OpenSearch equivalents
 */

const SOLR_TO_OS_TYPE = {
    // String types
    'string':       'keyword',
    'strings':      'keyword',

    // Text types
    'text_general': 'text',
    'text_en':      'text',
    'text_ws':      'text',
    'text':         'text',

    // Numeric types
    'pint':     'integer',
    'pints':    'integer',
    'int':      'integer',
    'plong':    'long',
    'plongs':   'long',
    'long':     'long',
    'pfloat':   'float',
    'pfloats':  'float',
    'float':    'float',
    'pdouble':  'double',
    'pdoubles': 'double',
    'double':   'double',

    // Date types
    'pdate':  'date',
    'pdates': 'date',
    'date':   'date',

    // Other types
    'boolean':  'boolean',
    'booleans': 'boolean',
    'binary':   'binary'
};

function getFieldType(fieldDef) {
    return fieldDef instanceof Map ? fieldDef.get('type') : fieldDef.type;
}

function setFieldType(fieldDef, osType) {
    if (fieldDef instanceof Map) {
        fieldDef.set('type', osType);
    } else {
        fieldDef.type = osType;
    }
}

function getNestedProperties(fieldDef) {
    return fieldDef instanceof Map ? fieldDef.get('properties') : fieldDef.properties;
}

function transformProperties(properties) {
    if (!properties || typeof properties !== 'object') return;

    const entries = properties instanceof Map ? properties.entries() : Object.entries(properties);
    for (const [, fieldDef] of entries) {
        if (!fieldDef || typeof fieldDef !== 'object') continue;

        const type = getFieldType(fieldDef);
        if (type && SOLR_TO_OS_TYPE[type]) {
            setFieldType(fieldDef, SOLR_TO_OS_TYPE[type]);
        }

        const nested = getNestedProperties(fieldDef);
        if (nested) {
            transformProperties(nested);
        }
    }
}

function getFromMap(obj, key) {
    return obj instanceof Map ? obj.get(key) : (obj ? obj[key] : undefined);
}

function main(context) {
    return function(metadata) {
        var body = getFromMap(metadata, 'body');
        var mappings = getFromMap(body, 'mappings');
        var properties = getFromMap(mappings, 'properties');
        if (properties) {
            transformProperties(properties);
        }
        return metadata;
    };
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { main, SOLR_TO_OS_TYPE };
}

(() => main)();
