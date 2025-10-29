/**
 * Transforms either an index template or index to include 'max_ngram_diff' with
 * the value specified below.  This limit was added in more recent versions of Opensearch
 * and needs to be used to unblock some scenarios.
 */
function main(context) {
  const NGRAM_DIFF_ALLOWED = 30;

  const isMap = (v) => v instanceof Map;
  const isObj = (v) => v && typeof v === "object";
  const asEntries = (v) => isMap(v) ? [...v.entries()] : Object.entries(v ?? {});
  const hasKey = (v, k) => isMap(v) ? v.has(k) : v != null && Object.prototype.hasOwnProperty.call(v, k);
  const getKey = (v, k) => (isMap(v) ? v.get(k) : v?.[k]);
  const setKey = (v, k, val) => (isMap(v) ? v.set(k, val) : (v[k] = val));

  const getPath = (root, path) => {
    let cur = root;
    for (const k of path) {
      if (!isObj(cur) || !hasKey(cur, k)) return undefined;
      cur = getKey(cur, k);
    }
    return cur;
  };

  const ensurePath = (root, path, preferMapFrom) => {
    let cur = root;
    for (let i = 0; i < path.length; i++) {
      const k = path[i];
      if (!hasKey(cur, k) || !isObj(getKey(cur, k))) {
        const useMap = isMap(preferMapFrom ?? cur);
        setKey(cur, k, useMap ? new Map() : {});
      }
      cur = getKey(cur, k);
    }
    return cur;
  };

  const setIndexMaxNgramDiff = (settings) => {
    if (!isObj(settings)) {
      return;
    }

    if (!hasKey(settings, "index")) {
      const container = isMap(settings) ? new Map() : {};
      setKey(settings, "index", container);
    }

    const indexContainer = getKey(settings, "index");
    if (isObj(indexContainer)) {
      setKey(indexContainer, "max_ngram_diff", NGRAM_DIFF_ALLOWED);
    }
  };

  const containsNGram = (analysis) => {
    if (!isObj(analysis)) {
      return false;
    }

    for (const sectionKey of ["tokenizer", "filter"]) {
      const section = getKey(analysis, sectionKey);
      if (!isObj(section)) continue;
      for (const [, cfg] of asEntries(section)) {
        const t = isObj(cfg) ? getKey(cfg, "type") : undefined;
        if (typeof t === "string" && t.toLowerCase().includes("ngram")) {
          return true;
        }
      }
    }
    return false;
  };

  const maybeApplySettingFromSettingsRoot = (root) => {
    if (!isObj(root)) {
      return;
    }

    let settings = getKey(root, "settings");
    if (isObj(settings)) {
      // Check for analysis at settings.analysis
      const analysis = getKey(settings, "analysis");
      if (containsNGram(analysis)) {
        setIndexMaxNgramDiff(settings);
        return;
      }
      
      // Check for analysis at settings.index.analysis
      const indexAnalysis = getPath(settings, ["index", "analysis"]);
      if (containsNGram(indexAnalysis)) {
        setIndexMaxNgramDiff(settings);
        return;
      }
      return;
    }

    const analysis = getPath(root, ["settings", "analysis"]) || getKey(root, "analysis");
    if (containsNGram(analysis)) {
      const createdSettings = ensurePath(root, ["settings"], root);
      setIndexMaxNgramDiff(createdSettings);
    }
  };

  const isTemplateCreationRequest = (doc) => {
    if (!isObj(doc)) return false;
    if (!(hasKey(doc, "type") && hasKey(doc, "name") && hasKey(doc, "body"))) {
      return false;
    }
    const type = getKey(doc, "type");
    return (type === "template" || type === "indexTemplate" || type === "componentTemplate");
  };
  
  const isIndexCreationRequest = (doc) => {
    if (!isObj(doc)) return false;
    if (!(hasKey(doc, "type") && hasKey(doc, "name") && hasKey(doc, "body"))) {
      return false;
    }
    const type = getKey(doc, "type");
    return (type === "index");
  };

  const processDocument = (doc) => {
    try {
      if (isTemplateCreationRequest(doc) || isIndexCreationRequest(doc)) {
        const body = getKey(doc, "body");
        maybeApplySettingFromSettingsRoot(body);
        return doc;
      }

      return doc;
    } catch (err) {
      console.error("Error in NGramDiffSettingTransformer:", err);
      return doc;
    }
  }

  return (document) => {
    if (Array.isArray(document)) { 
      return document.map(processDocument);
    }
    return processDocument(document);
  };
}

// Visibility for testing
if (typeof module !== "undefined" && module.exports) {
  module.exports = main;
}

(() => main)();
