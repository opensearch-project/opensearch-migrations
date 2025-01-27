function isEnabled(features, path) {
    if (!features) return true;

    let value = features;
    const keys = path.split('.');

    for (const key of keys) {
        if (value && typeof value === 'object' && key in value) {
            value = value[key];
        } else {
            value = null;
            break;
        }
    }

    if (typeof value === 'boolean') return value;
    if (value && typeof value === 'object' && 'enabled' in value) {
        return Boolean(value.enabled);
    }
    return false;
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

function convertSourceIndexToTargetViaRegex(sourceIndex, sourceType, regexIndexMappings) {
    const conjoinedSource = `${sourceIndex}/${sourceType}`;
    for (const [idxRegex, typeRegex, targetIdxPattern] of regexIndexMappings) {
        // Add start (^) and end ($) anchors to the regex to ensure it matches the entire string
        const conjoinedRegexString = `^${idxRegex}/${typeRegex}$`;
        const conjoinedRegex = new RegExp(conjoinedRegexString);
        if (conjoinedRegex.test(conjoinedSource)) {
            return conjoinedSource.replace(conjoinedRegex, targetIdxPattern);
        }
    }
    return null;
}

function convertSourceIndexToTarget(sourceIndex, sourceType, indexMappings, regexIndexMappings) {
    if (indexMappings[sourceIndex]) {
        return indexMappings[sourceIndex][sourceType];
    }
    return convertSourceIndexToTargetViaRegex(sourceIndex, sourceType, regexIndexMappings);
}

function makeNoopRequest() {
    return {
        method: "GET",
        URI: "/",
        protocol: "HTTP/1.0"
    }
}

function rewriteDocRequest(match, inputMap) {
    const targetIndex = convertSourceIndexToTarget(
        match[1],
        match[2],
        inputMap.index_mappings,
        inputMap.regex_index_mappings
    );

    if (!targetIndex) return makeNoopRequest();
    inputMap.request.URI = `/${targetIndex}/_doc/${match[3]}`;
    return inputMap.request;
}

function retargetCommandParameters(parameters, targetIndex) {
    parameters._index = targetIndex;
    delete parameters._type;
    return parameters;
}

function rewriteBulk(match, context) {
    const lines = context.request.payload.inlinedJsonSequenceBodies;
    const newLines = [];
    let ndi = 0;

    while (ndi < lines.length) {
        const command = lines[ndi++];
        const commandType = Object.keys(command)[0];
        const commandParameters = command[commandType] || {};

        // Next line is doc if it's not a 'delete' command.
        let doc = null;
        if (commandType !== 'delete' && ndi < lines.length) {
            doc = lines[ndi++];
        }

        const typeName = commandParameters._type ?? "_doc";

        // Convert source index to target index.
        const targetIndex = convertSourceIndexToTarget(
            commandParameters._index,
            typeName,
            context.index_mappings,
            context.regex_index_mappings
        );

        // If no valid target index, skip.
        if (!targetIndex) {
            continue;
        }
        retargetCommandParameters(commandParameters, targetIndex);
        newLines.push(command);
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
    var majorVersion = inputMap.properties?.version?.major;
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

    var newProperties = {}
    for (const [sourceType, ] of targetIndicesMap) {
        for (fieldName of Object.keys(oldMappings[sourceType].properties)) {
            if (newProperties[fieldName]) {
                const previouslyProcessedFieldDef = newProperties[fieldName];
                const currentlyProcessingFieldDef = oldMappings[sourceType].properties[fieldName];
                if (!deepEquals(previouslyProcessedFieldDef, currentlyProcessingFieldDef)) {
                    throw new Error("Cannot create union of different types for field " + fieldName);
                }
            }
            else {
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
    const types = Object.keys(mappings);
    const sourceTypeToTargetIndicesMap = new Map(types
        .map(type => [type, convertSourceIndexToTarget(sourceIndex, type, inputMap.index_mappings, inputMap.regex_index_mappings)])
        .filter(([, targetIndex]) => targetIndex) // Keep only entries with valid target indices
    );
    return createIndexAsUnionedExcise(sourceTypeToTargetIndicesMap, inputMap);
}

// Define regex patterns as constants
const PUT_POST_DOC_REGEX = /(?:PUT|POST) \/([^\/]*)\/([^\/]*)\/(.*)/;
const GET_DOC_REGEX      = /GET \/(?!\.{1,2}(?:\/|$))([^-_+][^A-Z\\/*?\"<>|,# ]*)\/(?!\.{1,2}(?:\/|$))([^-_+][^A-Z\\/*?\"<>|,# ]*)\/([^\/]+)$/;
const BULK_REQUEST_REGEX = /(?:PUT|POST) \/_bulk/;
const CREATE_INDEX_REGEX = /(?:PUT|POST) \/([^\/]*)/;

function routeHttpRequest(source_document, context) {
    const methodAndUri = `${source_document.method} ${source_document.URI}`;
    const documentAndContext = {
        request: source_document,
        index_mappings: context.index_mappings,
        regex_index_mappings: context.regex_index_mappings,
        properties: context.source_properties
    };
    return route(
        documentAndContext,
        methodAndUri,
        context.flags,
        () => (source_document),
        [
            [PUT_POST_DOC_REGEX, rewriteDocRequest, 'rewrite_add_request_to_strip_types'],
            [GET_DOC_REGEX, rewriteDocRequest, 'rewrite_get_request_to_strip_types'],
            [BULK_REQUEST_REGEX, rewriteBulk, 'rewrite_bulk'],
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
        context.regex_index_mappings
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

    if ("method" in document && "URI" in document) {
        return routeHttpRequest(document, context);
    } else if ("index" in document && "source" in document) {
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
    return (document) => detectAndTransform(document, context);
}

// Entrypoint function
(() => main)()