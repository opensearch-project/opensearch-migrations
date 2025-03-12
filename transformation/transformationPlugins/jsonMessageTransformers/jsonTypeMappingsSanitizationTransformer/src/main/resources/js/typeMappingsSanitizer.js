function isEnabled(features, path) {
    if (!features) return true;

    let value = features;
    const keys = path.split('.');

    for (const key of keys) {
        if (value && value instanceof Map && value.has(key)) {
            value = value[key];
        } else {
            value = null;
            break;
        }
    }

    if (typeof value === 'boolean') return value;
    if (value && value instanceof Map && value.has('enabled')) {
        return Boolean(value.enabled);
    }
    return false;
}

function retargetCommandParameters(parameters, targetIndex) {
    // Remove the '_type' key
    parameters.delete('_type');
    // Add the '_index' key with the new target index if exists
    if (targetIndex) {
        parameters.set('_index', targetIndex);
    } else {
        parameters.delete('_index');
    }
    return parameters;
}


function route(input, fieldToMatch, featureFlags, defaultAction, routes) {
    let matched = false;

    for (const [pattern, actionFn, featureNameParam] of routes) {
        if (!matched) {
            const featureName = featureNameParam || actionFn;
            const match = fieldToMatch.match(new RegExp(pattern));

            if (match) {
                matched = true;
                if (isEnabled(featureFlags, featureName)) {
                    return actionFn(match, input);
                }
            }
        }
    }

    return defaultAction(input);
}

function convertSourceIndexToTargetViaRegex(sourceIndex, sourceType, regexMappings) {
    const conjoinedSource = `${sourceIndex}/${sourceType}`;
    for (const { sourceIndexPattern, sourceTypePattern, targetIndexPattern } of regexMappings) {
        // Add start (^) and end ($) anchors to ensure the entire string is matched
        const conjoinedRegexString = `^${sourceIndexPattern}/${sourceTypePattern}$`;
        const conjoinedRegex = new RegExp(conjoinedRegexString);
        if (conjoinedRegex.test(conjoinedSource)) {
            return conjoinedSource.replace(conjoinedRegex, targetIndexPattern);
        }
    }
    return null;
}

function convertSourceIndexToTarget(sourceIndex, sourceType, indexMappings, regexMappings) {
    if (indexMappings[sourceIndex]) {
        return indexMappings[sourceIndex][sourceType];
    }
    return convertSourceIndexToTargetViaRegex(sourceIndex, sourceType, regexMappings);
}

function makeNoopRequest() {
    return new Map([
        ["method", "GET"],
        ["URI", "/"],
        ["protocol", "HTTP/1.0"]
    ]);
}

function rewriteDocRequest(match, inputMap) {
    const targetIndex = convertSourceIndexToTarget(
        match[1],
        match[2],
        inputMap.index_mappings,
        inputMap.regex_mappings
    );

    if (!targetIndex) return makeNoopRequest();
    inputMap.request.URI = `/${targetIndex}/_doc/${match[3]}`;
    return inputMap.request;
}


function rewriteBulk(match, context) {
    const lines = context.request.payload.inlinedJsonSequenceBodies;
    const newLines = [];
    let ndi = 0;

    let defaultSourceIndex = null;
    let defaultType = "_doc";
    if (match.length === 3) {
        // Case: /{index}/{type}/_bulk
        defaultSourceIndex = match[1];
        defaultType = match[2];
    } else if (match.length === 2) {
        // Case: /{index}/_bulk (default type _doc)
        defaultSourceIndex = match[1];
    }

    let defaultTargetIndex = null;
    if (defaultSourceIndex) {
        defaultTargetIndex = convertSourceIndexToTarget(defaultSourceIndex,
            defaultType,
            context.index_mappings,
            context.regex_mappings);
        const patternToReplace = /^.*\/(.*\/)?_bulk/;
        // Replace the pattern in the URI with the converted target index, if available.
        // If no valid conversion is found, remove the source index/type segment and default to '/_bulk'.
        context.request.URI = defaultTargetIndex
            ? context.request.URI.replace(patternToReplace, `/${defaultTargetIndex}/_bulk`)
            : context.request.URI.replace(patternToReplace, "/_bulk");
    }

    while (ndi < lines.length) {
        const command = lines[ndi++];
        const commandType = command.keys().next().value;
        let commandParameters = command[commandType] || {};

        // Next line is doc if it's not a 'delete' command.
        let doc = null;
        if (commandType !== 'delete' && ndi < lines.length) {
            doc = lines[ndi++];
        }

        // Use command index or default source index if available
        const sourceIndex = commandParameters._index || defaultSourceIndex;
        // Use provided _type if exists, otherwise use the defaultType
        const typeName = commandParameters._type ?? defaultType;

        // Convert source index to target index.
        const targetIndex = convertSourceIndexToTarget(
            sourceIndex,
            typeName,
            context.index_mappings,
            context.regex_mappings
        );

        // If no valid target index, skip.
        if (!targetIndex) {
            continue;
        }

        // Update command parameters and ensure they're correctly inserted
        const targetIndexInBulk = targetIndex !== defaultTargetIndex ? targetIndex : null;
        commandParameters = retargetCommandParameters(commandParameters, targetIndexInBulk);
        const updatedCommand = { [commandType]: commandParameters };
        newLines.push(updatedCommand);
        if (doc) newLines.push(doc);
    }

    context.request.payload.inlinedJsonSequenceBodies = newLines;
    return context.request;
}

function includesTypeNames(inputMap) {
    const flagMatch = inputMap.request.URI.match(new RegExp("[?&]include_type_name=([^&#]*)"));
    if (flagMatch) {
        return JSON.parse(flagMatch[1]);
    }
    const majorVersion = inputMap.properties?.version?.major;
    if (majorVersion >= 7) {
        return false;
    } else if (majorVersion <= 6) {
        return true;
    } else {
        throw new Error("include_type_name was not set on the incoming URI." +
            "The template needs to know what version the original request was targeted for " +
            "in order to properly understand the semantics and what was intended.  " +
            "Without that, this transformation cannot map the request " +
            "to an unambiguous request for the target");
    }
}

function deepEqualsMaps(map1, map2) {
    if (map1.size !== map2.size) return false;

    for (const [key, value1] of map1) {
        if (!map2.has(key)) return false;

        const value2 = map2.get(key);

        if (!deepEquals(value1, value2)) return false;
    }

    return true;
}

function deepEquals(value1, value2) {
    if (value1 === value2) return true; // Handles primitives and identical references

    if (value1 instanceof Map && value2 instanceof Map) {
        return deepEqualsMaps(value1, value2); // Recurse for maps
    }

    if (value1 && value2 && typeof value1 === 'object' && typeof value2 === 'object') {
        return deepEqualsObjects(value1, value2); // Recurse for objects
    }

    return false; // Otherwise, values are not deeply equal
}

function deepEqualsObjects(obj1, obj2) {
    const keys1 = Object.keys(obj1);
    const keys2 = Object.keys(obj2);

    if (keys1.length !== keys2.length) return false;

    return keys1.every(key => key in obj2 && deepEquals(obj1[key], obj2[key]));
}

function createIndexAsUnionedExcise(targetIndicesMap, inputMap) {
    const request = inputMap.request;
    const jsonBody = request.payload.inlinedJsonBody;

    const targetIndices = [...new Set(targetIndicesMap.values())];

    if (targetIndices.length === 0) {
        return makeNoopRequest();
    } else if (targetIndices.length > 1) {
        throw new Error("Cannot specify multiple indices to create with a single request and cannot yet " +
            "represent multiple requests with the request format. Attempting to create request for " + targetIndices.join(", "));
    }
    const oldMappings = jsonBody.mappings;
    const targetIndex = targetIndices.at(0);
    request.URI = "/" + targetIndex;

    const newProperties = {}
    for (const [sourceType,] of targetIndicesMap) {
        for (fieldName of oldMappings[sourceType].properties.keys()) {
            if (newProperties[fieldName]) {
                const previouslyProcessedFieldDef = newProperties[fieldName];
                const currentlyProcessingFieldDef = oldMappings[sourceType].properties[fieldName];
                if (!deepEquals(previouslyProcessedFieldDef, currentlyProcessingFieldDef)) {
                    throw new Error("Cannot create union of different types for field " + fieldName);
                }
            } else {
                newProperties[fieldName] = oldMappings[sourceType].properties[fieldName];
            }
        }
    }
    jsonBody.mappings = {properties: newProperties};
    return inputMap.request;
}

function rewriteCreateIndex(match, inputMap) {
    const body = inputMap.request.payload.inlinedJsonBody || {};
    const mappings = body.mappings;

    if (!mappings || (mappings.properties && !mappings.properties?.properties) || !includesTypeNames(inputMap)) {
        // Do not modify if types are not found or if include_type_name is not set
        return inputMap.request;
    }

    const sourceIndex = match[1].replace(new RegExp("[?].*"), ""); // remove the query string that could be after
    const types = [...mappings.keys()];
    const sourceTypeToTargetIndicesMap = new Map(types
        .map(type => [type, convertSourceIndexToTarget(sourceIndex, type, inputMap.index_mappings, inputMap.regex_mappings)])
        .filter(([, targetIndex]) => targetIndex) // Keep only entries with valid target indices
    );
    return createIndexAsUnionedExcise(sourceTypeToTargetIndicesMap, inputMap);
}

// Define regex patterns as constants
const PUT_POST_DOC_REGEX = /(?:PUT|POST) \/([^/]*)\/([^/]*)\/(.*)/;
const GET_DOC_REGEX = /GET \/(?!\.{1,2}(?:\/|$))([^-_+][^A-Z/*?"<>|,# ]*)\/(?!\.{1,2}(?:\/|$))([^-_+][^A-Z/*?"<>|,# ]*)\/([^/]+)$/;
const BULK_REQUEST_REGEX = /(?:PUT|POST) \/_bulk/;
const CREATE_INDEX_REGEX = /(?:PUT|POST) \/([^/]*)/;
const INDEX_BULK_REQUEST_REGEX = /(?:PUT|POST) \/([^/]+)\/_bulk/;
const INDEX_TYPE_BULK_REQUEST_REGEX = /(?:PUT|POST) \/([^/]+)\/([^/]+)\/_bulk/;

function processMetadataRequest(document, context) {
    let mappings = document?.body?.mappings;

    // Normalize mappings to an object
    if (Array.isArray(mappings)) {
        // If it's an array, convert it to a map by merging entries
        const merged = new Map();
        for (const mapping of mappings) {
            for (const [key, value] of mapping.entries()) {
                merged.set(key, value);
            }
        }
        mappings = merged;
        }

    if (!mappings || (mappings.properties && !mappings.properties?.properties)) {
        // No Type Exists either because mappings doesn't exist,
        // or properties directly under mappings (and did not find a type named "properties")

        const typeName = "_doc";

        // Convert source index to target index.
        const targetIndex = convertSourceIndexToTarget(
            document.name,
            typeName,
            context.index_mappings,
            context.regex_mappings
        );

        if (targetIndex) {
            document.name = targetIndex;
            // Transform composed_of names if present to make valid index_templates
            if (document.body?.composed_of && Array.isArray(document.body.composed_of)) {
                document.body.composed_of = document.body.composed_of.map(compName => {
                    const transformed = convertSourceIndexToTarget(
                        compName,
                        "_doc",
                        context.index_mappings,
                        context.regex_mappings
                    );
                    return transformed || compName;
                });
            }

            return [document];
        }
        // Index excluded, skip
        return [];
    }

    const creationObjects = new Map();
    for (const [type, mappingValue] of mappings.entries()) {
        const targetIndex = convertSourceIndexToTarget(
            document.get('name'),
            type,
            context.index_mappings,
            context.regex_mappings
        );

        if (targetIndex) {
            if (creationObjects.has(targetIndex)) {
                const existing = creationObjects.get(targetIndex);
                const body = existing.get('body');
                const mappingsMap = body.get('mappings');
                const docMapping = mappingsMap.get('_doc');

                // Merge the 'properties' Maps:
                const existingProperties = docMapping.get('properties');
                const newProperties = mappingValue.get('properties');
                const mergedProperties = new Map([...existingProperties, ...newProperties]);
                docMapping.set('properties', mergedProperties);
            } else {
                const newMetadataItem = deepCloneMap(document);
                newMetadataItem.set('name', targetIndex);
                // Replace the mappings with a new Map containing '_doc'
                newMetadataItem.get('body').set('mappings', new Map([['_doc', mappingValue]]));
                creationObjects.set(targetIndex, newMetadataItem);
            }
        }
    }
    return [...creationObjects.values()];
}

// Helper function to deep clone a Map, including nested Maps/objects
function deepCloneMap(map) {
    const clone = new Map();
    for (const [key, value] of map.entries()) {
        let clonedValue;
        if (value instanceof Map) {
            clonedValue = deepCloneMap(value);
        } else if (Array.isArray(value)) {
            clonedValue = value.map(item => (item instanceof Map ? deepCloneMap(item) : item));
        } else if (value !== null && typeof value === 'object') {
            // For plain objects, use JSON methods (assumes JSON-safe objects)
            clonedValue = JSON.parse(JSON.stringify(value));
        } else {
            clonedValue = value;
        }
        clone.set(key, clonedValue);
    }
    return clone;
}


function routeHttpRequest(source_document, context) {
    const methodAndUri = `${source_document.method} ${source_document.URI}`;
    const documentAndContext = {
        request: source_document,
        index_mappings: context.index_mappings,
        regex_mappings: context.regex_mappings,
        properties: context.source_properties
    };
    return route(
        documentAndContext,
        methodAndUri,
        context.flags,
        () => (source_document),
        [
            [INDEX_TYPE_BULK_REQUEST_REGEX, rewriteBulk, 'rewrite_bulk'],
            [INDEX_BULK_REQUEST_REGEX, rewriteBulk, 'rewrite_bulk'],
            [BULK_REQUEST_REGEX, rewriteBulk, 'rewrite_bulk'],
            [PUT_POST_DOC_REGEX, rewriteDocRequest, 'rewrite_add_request_to_strip_types'],
            [GET_DOC_REGEX, rewriteDocRequest, 'rewrite_get_request_to_strip_types'],
            [CREATE_INDEX_REGEX, rewriteCreateIndex, 'rewrite_create_index']
        ]
    );
}

function processBulkIndex(docBackfillPair, context) {
    const parameters = docBackfillPair.index
    const sourceIndexName = parameters._index;
    const typeName = parameters._type ?? "_doc";

    const targetIndex = convertSourceIndexToTarget(
        sourceIndexName,
        typeName,
        context.index_mappings,
        context.regex_mappings
    );

    if (!targetIndex) return [];

    docBackfillPair.index = retargetCommandParameters(parameters, targetIndex);
    return docBackfillPair;
}

// Replacer function for console.log to print nested maps
function mapToPlainObjectReplacer(key, value) {
    // Check if the value is a Map, and convert it to an object
    if (value instanceof Map) {
        return Object.fromEntries(value);
    }
    return value; // Return the value as-is for other types
}

function detectAndTransform(document, context) {
    if (!document) {
        throw new Error("No source_document was defined - nothing to transform!");
    }
    if (document.has("type") && document.has("name") && document.has("body")) {
        return processMetadataRequest(document, context);
    } else if (document.has("method") && document.has("URI")) {
        return routeHttpRequest(document, context);
    } else if (document.has("index") && document.has("source")) {
        return processBulkIndex(document, context);
    } else {
        return document;
    }
}

function main(context) {
    console.log("Context: ", JSON.stringify(context, mapToPlainObjectReplacer, 2));

    // Validate context, todo: include more validation
    if (!context || !context?.source_properties?.version?.major) {
        console.error("Context Missing source_properties: ", JSON.stringify(context, mapToPlainObjectReplacer, 2));
        throw new Error("No source_properties defined - required to transform correctly!");
    }
    // Support bulk processing
    return (document) => {
        if (Array.isArray(document)) {
            return document.flat().map(item => detectAndTransform(item, context));
        }
        return detectAndTransform(document, context);
    };
}

// Entrypoint function
(() => main)()