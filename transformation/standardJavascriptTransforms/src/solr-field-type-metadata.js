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

function convertSolrType(solrType) {
    return SOLR_TO_OS_TYPE[solrType] || 'text';
}

function transformProperties(properties) {
    if (!properties || typeof properties !== 'object') return properties;

    const entries = properties instanceof Map ? properties.entries() : Object.entries(properties);
    for (const [name, fieldDef] of entries) {
        if (!fieldDef || typeof fieldDef !== 'object') continue;

        const type = fieldDef instanceof Map ? fieldDef.get('type') : fieldDef.type;
        if (type && SOLR_TO_OS_TYPE[type]) {
            const osType = convertSolrType(type);
            if (fieldDef instanceof Map) {
                fieldDef.set('type', osType);
            } else {
                fieldDef.type = osType;
            }
        }

        // Recurse into nested properties
        const nested = fieldDef instanceof Map ? fieldDef.get('properties') : fieldDef.properties;
        if (nested) {
            transformProperties(nested);
        }
    }
    return properties;
}

function main(context) {
    return function(metadata) {
        if (!metadata) return metadata;

        const body = metadata instanceof Map ? metadata.get('body') : metadata.body;
        if (!body) return metadata;

        const mappings = body instanceof Map ? body.get('mappings') : body.mappings;
        if (!mappings) return metadata;

        const properties = mappings instanceof Map ? mappings.get('properties') : mappings.properties;
        if (properties) {
            transformProperties(properties);
        }

        return metadata;
    };
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { main, convertSolrType, SOLR_TO_OS_TYPE };
}

(() => main)();
