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
        const conjoinedRegex = `${idxRegex}/${typeRegex}`;
        const match = conjoinedSource.match(new RegExp(conjoinedRegex));

        if (match) {
            return conjoinedSource.replace(new RegExp(conjoinedRegex), targetIdxPattern);
        }
    }
    return null;
}

function convertSourceIndexToTarget(sourceIndex, sourceType, indexMappings, regexIndexMappings) {
    if (!sourceType || sourceType === '_doc') return sourceIndex;

    const targetIndex = indexMappings[sourceIndex]?.[sourceType] ||
        convertSourceIndexToTargetViaRegex(sourceIndex, sourceType, regexIndexMappings);

    return targetIndex;
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

    for (let ndi = 0; ndi < lines.length; ndi) {
        const command = lines[ndi++];
        const commandType = Object.keys(command)[0];
        const parameters = command[commandType];

        if (ndi >= lines.length) {
            break;
        }

        const typeName = parameters._type;
        const targetIndex = convertSourceIndexToTarget(
            parameters._index,
            typeName,
            context.index_mappings,
            context.regex_index_mappings
        );
        if (!targetIndex) {
            continue;
        }

        if (commandType === 'delete') {
            retargetCommandParameters(parameters, targetIndex)
            newLines.push(command);
        } else {
            const doc = lines[ndi++];
            retargetCommandParameters(parameters, targetIndex)
            newLines.push(command);
            newLines.push(doc);
        }
    }

    context.request.payload.inlinedJsonSequenceBodies = newLines;
    context.request.URI = '/_bulk';
    return context.request;
}

function includesTypeNames(inputMap) {
    const flagMatch = inputMap.request.URI.match(new RegExp("[?&]include_type_name=([^&#]*)"));
    if (flagMatch) {
        return JSON.parse(flagMatch[1]);
    }
    var majorVersion = inputMap.properties.version.major;
    if (majorVersion) {
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
}

function createIndexAsUnionedExcise(sourceIndex, targetIndex, inputMap) {
    const request = inputMap.request;
    const jsonBody = request.payload.inlinedJsonBody;
    const sourceInputTypes = Object.keys(inputMap.index_mappings[sourceIndex]);
    var newProperties = {type: { type: "keyword"} }
    for (sourceType of sourceInputTypes) {
        const oldType = jsonBody.mappings[sourceType]
        if (!oldType) {
            continue;
        }
        for (fieldName of Object.keys(oldType.properties)) {
            newProperties[fieldName] = oldType.properties[fieldName];
        }
    }
    request.URI = "/" + targetIndex;
    jsonBody.mappings = {properties: newProperties};
    return inputMap.request;
}

function rewriteCreateIndex(match, inputMap) {
    const body = inputMap.request.payload.inlinedJsonBody || {};
    const mappings = body.mappings;

    if (!mappings || !includesTypeNames(inputMap)) {
        return inputMap.request;
    }

    const sourceIndex = match[1].replace(new RegExp("[?].*"), ""); // remove the query string that could be after
    const types = Object.keys(mappings);
    if (types.length === 0) {
        return inputMap.request;
    }

    const targetIndicesList = types
        .map(type => convertSourceIndexToTarget(sourceIndex, type, inputMap.index_mappings, inputMap.regex_index_mappings))
        .filter(Boolean);
    const targetIndices = [...new Set(targetIndicesList)];

    if (targetIndices.length === 0) {
        return makeNoopRequest();
    } else if (targetIndices.length == 1) {
        return createIndexAsUnionedExcise(sourceIndex, targetIndices[0], inputMap);
    } else {
        throw new Error("Cannot specify multiple indices to create with a single request and cannot yet " +
            "represent multiple requests with the request format.");
    }
}

function routeHttpRequest(source_document, context) {
    const methodAndUri = `${source_document.method} ${source_document.URI}`;
    const documentAndContext = {
        request: source_document,
        index_mappings: context.index_mappings,
        regex_index_mappings: context.regex_index_mappings,
        properties: context.properties
    }

    return route(
        documentAndContext,
        methodAndUri,
        context.flags,
        () => (source_document),
        [
            [/(?:PUT|POST) \/([^\/]*)\/([^\/]*)\/(.*)/, rewriteDocRequest, 'rewrite_add_request_to_strip_types'],
            [/GET \/(?!\.{1,2}(?:\/|$))([^-_+][^A-Z\\/*?\"<>|,# ]*)\/(?!\.{1,2}(?:\/|$))([^-_+][^A-Z\\/*?\"<>|,# ]*)\/([^\/]+)$/, rewriteDocRequest, 'rewrite_get_request_to_strip_types'],
            [/(?:PUT|POST) \/_bulk/, rewriteBulk, 'rewrite_bulk'],
            [/(?:PUT|POST) \/([^\/]*)/, rewriteCreateIndex, 'rewrite_create_index']
        ]
    );
}

function processBulkIndex(docBackfillPair, context) {
    const parameters = docBackfillPair.index
    const typeName = parameters._type;
    if (!typeName) return docBackfillPair

    const targetIndex = convertSourceIndexToTarget(
        parameters._index,
        typeName,
        context.index_mappings,
        context.regex_index_mappings
    );

    if (!targetIndex) return null;

    docBackfillPair.index = retargetCommandParameters(parameters, targetIndex);
    return docBackfillPair;
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